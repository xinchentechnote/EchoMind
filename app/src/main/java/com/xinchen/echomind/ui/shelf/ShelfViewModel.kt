package com.xinchen.echomind.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import android.util.Log
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelfUiState(
    val rows: List<ShelfRow> = emptyList(),
    val tab: ShelfTab = ShelfTab.All,
    val query: String = "",
    val isBusy: Boolean = false,
    val lastPlayed: ShelfRow? = null,
    val folders: List<ImportedFolderEntity> = emptyList(),
    val message: String? = null,
)

class ShelfViewModel(private val repository: AudioRepository) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    init {
        // tab/query 只存在 _state 里：再开一份私有 MutableStateFlow 就会出现
        // 「列表跟着 tab 变了、高亮却读旧值」的分裂状态
        viewModelScope.launch {
            _state.map { it.tab to it.query }.distinctUntilChanged()
                .flatMapLatest { (t, q) -> repository.shelf(t, q) }
                .collect { rows -> _state.update { it.copy(rows = rows) } }
        }
        viewModelScope.launch {
            repository.lastPlayed().distinctUntilChanged()
                .collect { last -> _state.update { it.copy(lastPlayed = last) } }
        }
        viewModelScope.launch {
            repository.folders().collect { folders -> _state.update { it.copy(folders = folders) } }
        }
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

    fun importFolder(treeUri: Uri) = runBusy {
        val outcome = repository.importFolder(treeUri)
        buildString {
            append("新增 ${outcome.added} 首")
            if (outcome.alreadyOnShelf > 0) append("，${outcome.alreadyOnShelf} 首已在书架")
            if (outcome.changed > 0) append("，更新 ${outcome.changed} 首")
            if (outcome.vanished > 0) append("，${outcome.vanished} 首文件已不在")
            if (outcome.revived > 0) append("，${outcome.revived} 首文件已回来")
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
        buildString {
            append("重新扫描完成：新增 $added，更新 $changed，消失 $vanished")
            if (revived > 0) append("，$revived 首文件已回来")
            if (onShelf > 0) append("，已在书架 $onShelf")
            if (revoked > 0) append("，$revoked 个文件夹授权已失效，请重新授权")
        }
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
                ShelfViewModel(app.graph.audioRepository)
            }
        }
    }
}
