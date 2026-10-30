package com.xinchen.echomind.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import android.util.Log
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.asr.ModelCatalog
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ImportOutcome
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.data.repo.DeviceAudioFolders
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import com.xinchen.echomind.domain.importing.RelativePathIndex
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.ui.transcript.TranscriptDisplay
import com.xinchen.echomind.ui.transcript.TranscriptOutcomeCopy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 书架目录浏览（2026-10-28，VidNest 式逐层下钻）。null = 平铺（搜索词或非「全部」tab）。 */
sealed interface ShelfBrowser {

    /** 文件夹单元（一个已导入文件夹 = 一个可进入的根） */
    data class RootFolder(val folderId: String, val displayName: String, val count: Int)

    /** 根层：只列**有内容**的文件夹（书数 0 的不出现） */
    data class Root(val folders: List<RootFolder>) : ShelfBrowser

    /**
     * 文件夹内：按夹内相对路径分层。
     * [path] 相对夹根（"" = 夹根）；本机伪文件夹的书路径带设备目录前缀，展示前已剥掉。
     */
    data class Inside(
        val folderId: String,
        val folderName: String,
        val path: String,
        val folders: List<RelativePathIndex.FolderNode>,
        val books: List<ShelfRow>,
    ) : ShelfBrowser
}

data class ShelfUiState(
    val rows: List<ShelfRow> = emptyList(),
    val tab: ShelfTab = ShelfTab.All,
    val query: String = "",
    val isBusy: Boolean = false,
    val lastPlayed: ShelfRow? = null,
    val folders: List<ImportedFolderEntity> = emptyList(),
    val message: String? = null,
    /** 非 null = 目录浏览视图（「全部」tab 且无搜索词）；平铺行 [rows] 此时不用 */
    val browser: ShelfBrowser? = null,
)

class ShelfViewModel(
    private val repository: AudioRepository,
    /**
     * 一键转录的出口（用户口径，2026-10-24）：书架行上「未转录」旁边的小按钮直接入队，不再绕到转录页确认。
     * 返回给用户看的一句话。lambda 接缝：入队 + 排 WorkManager 那半边要 Android，JVM 里用替身。
     */
    private val startTranscription: suspend (audioId: String) -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    private val allRows = MutableStateFlow<List<ShelfRow>>(emptyList())
    private val foldersFlow = MutableStateFlow<List<ImportedFolderEntity>>(emptyList())

    /** 浏览位置：null folderId = 根层（列文件夹） */
    private val browse = MutableStateFlow(BrowsePosition())

    data class BrowsePosition(val folderId: String? = null, val path: String = "")

    init {
        viewModelScope.launch {
            _state.map { it.tab to it.query }.distinctUntilChanged()
                .flatMapLatest { (t, q) -> repository.shelf(t, q) }
                .collect { rows -> allRows.value = rows }
        }
        viewModelScope.launch {
            repository.folders().collect { foldersFlow.value = it }
        }
        viewModelScope.launch {
            repository.lastPlayed().distinctUntilChanged()
                .collect { last -> _state.update { it.copy(lastPlayed = last) } }
        }
        viewModelScope.launch {
            combine(
                allRows,
                foldersFlow,
                browse,
                _state.map { it.tab to it.query }.distinctUntilChanged(),
            ) { rows, folders, pos, (tab, query) ->
                buildBrowser(rows, folders, pos, tab, query) to rows
            }.collect { (browser, rows) ->
                // rows 喂平铺视图（搜索结果 / 最近播放 / 收藏）；browser 只在「全部 + 无搜索词」非空
                _state.update { it.copy(browser = browser, rows = rows) }
            }
        }
    }

    /**
     * 「全部」tab 且无搜索词 = 目录浏览（用户口径 2026-10-28：先见有内容的文件夹，逐层进去，
     * 不要一上来平铺全部）；其余情况回平铺。正被浏览的文件夹被移除时落回空根层。
     */
    private fun buildBrowser(
        rows: List<ShelfRow>,
        folders: List<ImportedFolderEntity>,
        pos: BrowsePosition,
        tab: ShelfTab,
        query: String,
    ): ShelfBrowser? {
        if (tab != ShelfTab.All || query.isNotEmpty()) return null
        if (pos.folderId == null) {
            return ShelfBrowser.Root(
                folders.mapNotNull { folder ->
                    val count = rows.count { it.folderId == folder.id }
                    if (count == 0) null else ShelfBrowser.RootFolder(folder.id, folder.displayName, count)
                }.sortedBy { it.displayName.lowercase() },
            )
        }
        val folder = folders.firstOrNull { it.id == pos.folderId }
            ?: return ShelfBrowser.Root(emptyList())
        // 本机伪文件夹的书 relative_path 带设备目录前缀（Music/我的_有声书/…），
        // 夹内分层前先剥掉；SAF 书本来就相对树根，剥前缀为空
        val strip = DeviceAudioFolders.parse(folder.treeUri)?.second?.let { "$it/" } ?: ""
        val entries = rows.filter { it.folderId == pos.folderId }.map { it.relativePath.removePrefix(strip) to it }
        val listing = RelativePathIndex(entries).childrenOf(pos.path)
        return ShelfBrowser.Inside(pos.folderId, folder.displayName, pos.path, listing.folders, listing.items)
    }

    fun onTabSelected(selected: ShelfTab) {
        _state.update { it.copy(tab = selected) }
    }

    fun onQueryChanged(text: String) {
        _state.update { it.copy(query = text) }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    fun browseInto(folderId: String) {
        browse.value = BrowsePosition(folderId, "")
    }

    fun browseIntoSub(path: String) {
        browse.update { it.copy(path = path) }
    }

    /** 返回键的语义：夹内先上退一层，根层才交给导航退出 */
    fun browseUp() {
        browse.update { pos ->
            when {
                pos.folderId == null -> pos
                pos.path.isEmpty() -> BrowsePosition(null, "")
                else -> BrowsePosition(pos.folderId, pos.path.substringBeforeLast('/', ""))
            }
        }
    }

    fun importFolder(treeUri: Uri) = runBusy {
        val outcome = repository.importFolder(treeUri)
        buildString {
            append("新增 ${outcome.added} 首")
            if (outcome.alreadyOnShelf > 0) append("，${outcome.alreadyOnShelf} 首已在书架")
            if (outcome.changed > 0) append("，更新 ${outcome.changed} 首")
            if (outcome.vanished > 0) append("，${outcome.vanished} 首文件已不在")
            if (outcome.revived > 0) append("，${outcome.revived} 首文件已回来")
            if (outcome.preparingWma > 0) append("，${outcome.preparingWma} 首 WMA 正在后台准备，完成后可播放")
            if (outcome.truncated) append("（目录过大或过深，已截断）")
        }
    }

    fun rescanAll() = runBusy {
        val outcomes = repository.rescanAll()
        if (outcomes.isEmpty()) return@runBusy "还没有导入任何文件夹"
        val added = outcomes.sumOf { it.added }
        val changed = outcomes.sumOf { it.changed }
        val vanished = outcomes.sumOf { it.vanished }
        val revived = outcomes.sumOf { it.revived }
        val onShelf = outcomes.sumOf { it.alreadyOnShelf }
        val revoked = outcomes.count { it.permissionRevoked }
        val preparingWma = outcomes.sumOf { it.preparingWma }
        buildString {
            append("重新扫描完成：新增 $added，更新 $changed，消失 $vanished")
            if (revived > 0) append("，$revived 首文件已回来")
            if (onShelf > 0) append("，已在书架 $onShelf")
            if (revoked > 0) append("，$revoked 个文件夹授权已失效，请重新授权")
            if (preparingWma > 0) append("，$preparingWma 首 WMA 正在后台准备")
        }
    }

    /** 只重扫一个文件夹（目录浏览器里文件夹行的 ⋮）；SAF 与本机伪文件夹走仓储同一段分支 */
    fun rescanFolder(folderId: String) = runBusy {
        val outcome = repository.rescanFolder(folderId)
        if (outcome.permissionRevoked) {
            "文件夹授权已失效，请重新授权"
        } else {
            buildString {
                append("文件夹已重扫：新增 ${outcome.added}，更新 ${outcome.changed}，消失 ${outcome.vanished}")
                if (outcome.revived > 0) append("，${outcome.revived} 首文件已回来")
                if (outcome.alreadyOnShelf > 0) append("，已在书架 ${outcome.alreadyOnShelf}")
                if (outcome.preparingWma > 0) append("，${outcome.preparingWma} 首 WMA 正在后台准备")
            }
        }
    }

    /** 移除整个文件夹的书架记录（级联删播放记忆；listening_session 历史保留，NFR-04） */
    fun removeFolder(folderId: String) = runBusy {
        val folder = foldersFlow.value.firstOrNull { it.id == folderId }
        val count = allRows.value.count { it.folderId == folderId }
        repository.removeFolder(folderId)
        if (browse.value.folderId == folderId) browse.value = BrowsePosition()
        "已移除「${folder?.displayName ?: "该文件夹"}」及其 $count 本书（收听历史保留）"
    }

    fun rename(audioId: String, title: String) = runBusy {
        if (title.isBlank()) return@runBusy "标题不能为空"
        repository.rename(audioId, title)
        null
    }

    fun toggleFavorite(audioId: String, favorite: Boolean) = runBusy {
        repository.setFavorite(audioId, favorite)
        null
    }

    fun removeBook(audioId: String) = runBusy {
        repository.removeBook(audioId)
        "已从书架移除（收听历史保留）"
    }

    /** 行上那个小「转录」按钮：直接入队并排上 WorkManager，结局一句话回给用户 */
    fun transcribeNow(audioId: String) = runBusy { startTranscription(audioId) }

    private fun runBusy(block: suspend () -> String?) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { current ->
                current.copy(
                    isBusy = false,
                    message = result.getOrElse { error ->
                        // UI 只给短句，细节进 logcat，否则「授权刚给就失效」这类问题无法定位
                        Log.w(TAG, "shelf action failed", error)
                        if (error is PermissionRevokedException) "文件夹授权已失效，请重新授权"
                        else "操作失败：${error.message ?: error.javaClass.simpleName}"
                    } ?: current.message,
                )
            }
        }
    }

    companion object {
        private const val TAG = "EchoMindShelf"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EchoMindApp
                ShelfViewModel(
                    app.graph.audioRepository,
                    startTranscription = { audioId ->
                        // 与首次播放自动转录同一个模型引用（FR-512）；守卫（已有任务/已转好）都在仓储层
                        val outcome = app.graph.transcriptRepository.enqueue(audioId, ModelCatalog.transcriptRef)
                        val copy = when (outcome) {
                            is EnqueueOutcome.Created ->
                                if (app.graph.transcriptWork.schedule(outcome.jobId, audioId)) TranscriptOutcomeCopy.Scheduled
                                else TranscriptOutcomeCopy.WorkRefused

                            is EnqueueOutcome.AlreadyActive -> TranscriptOutcomeCopy.AlreadyActive
                            EnqueueOutcome.AlreadyTranscribed -> TranscriptOutcomeCopy.AlreadyTranscribed
                            EnqueueOutcome.AudioNotFound -> TranscriptOutcomeCopy.BookGone
                            EnqueueOutcome.JobNotFound -> TranscriptOutcomeCopy.JobGone
                        }
                        TranscriptDisplay.enqueueOutcomeLabel(copy)
                    },
                )
            }
        }
    }
}
