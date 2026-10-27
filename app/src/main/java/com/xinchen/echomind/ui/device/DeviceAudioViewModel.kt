package com.xinchen.echomind.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.DeviceAudioFolderRef
import com.xinchen.echomind.domain.importing.DeviceAudioIndex
import com.xinchen.echomind.domain.importing.DeviceAudioListing
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import com.xinchen.echomind.domain.text.TitleDeriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 权限门的状态机：Unknown=还没查过，Denied=用户拒了一次（还能再请求），Blocked=系统不再弹窗（要去设置） */
enum class AudioPermissionState { Unknown, Granted, Denied, Blocked }

data class DeviceAudioUiState(
    val permission: AudioPermissionState = AudioPermissionState.Unknown,
    /** 当前 SDK 对应的运行时权限名（请求与「去设置」文案都用它） */
    val permissionName: String = "",
    /** null = 选卷；非空 = 在该卷里浏览（[DeviceAudioUiState.path] 为空表示卷根） */
    val volume: String? = null,
    val path: String? = null,
    val listing: DeviceAudioListing = DeviceAudioListing(emptyList(), emptyList()),
    val totalFound: Int = 0,
    val scanning: Boolean = false,
    /** 扫描/导入失败时的一句可读原因 */
    val scanError: String? = null,
    val importedUris: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val message: String? = null,
) {
    /** 系统返回键的语义：卷内上退一层，选卷页才交给导航退出 */
    val canGoUp: Boolean get() = volume != null

    fun goUp(): DeviceAudioUiState {
        val v = volume ?: return this
        val p = path
        // 约定：path == null 表示卷根（openFolder 同一归一化），非空才是真的子目录
        return if (p.isNullOrEmpty()) {
            copy(volume = null, path = null)
        } else {
            copy(path = p.substringBeforeLast('/', "").ifEmpty { null })
        }
    }
}

/**
 * 本机音频浏览页（2026-10-27）：MediaStore 全量扫描 → 内存索引逐层下钻 → 整夹/单文件导入书架。
 * 浏览数据不落库：MediaStore 本身就是持久索引，这里每次进入重扫一遍，避免多养一份会过期的副本。
 */
class DeviceAudioViewModel(private val repository: AudioRepository) : ViewModel() {

    private val _state = MutableStateFlow(DeviceAudioUiState())
    val state: StateFlow<DeviceAudioUiState> = _state.asStateFlow()

    /** 浏览用的全量扫描结果；listing 由 [relisting] 按当前卷/路径现算 */
    private var entries: List<DeviceAudioEntry> = emptyList()

    init {
        refreshPermission()
    }

    /**
     * 重查权限状态。从系统设置授权回来（ON_RESUME）也走这里：权限从「未授予」翻成「授予」的那一刻
     * 自动开扫；权限本来就有的普通 resume（如从播放页回来）不重扫，省一次 MediaStore 全量查询。
     */
    fun refreshPermission() {
        val access = repository.mediaAudioAccess ?: return
        val wasGranted = _state.value.permission == AudioPermissionState.Granted
        val granted = access.isGranted()
        _state.update {
            it.copy(
                permission = if (granted) AudioPermissionState.Granted else it.permission,
                permissionName = access.permissionName(),
            )
        }
        if (granted && (!wasGranted || entries.isEmpty())) scan()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            _state.update { it.copy(permission = AudioPermissionState.Granted) }
            scan()
        } else {
            // 第二次拒绝后系统不会再弹，UI 换成「去系统设置」；这里不区分「只拒一次」与「不再问」，
            // 因为 canAskAgain 要查 ActivityResultApi 之外的私有状态，拒两次自然收敛到同一个界面
            _state.update { it.copy(permission = AudioPermissionState.Blocked) }
        }
    }

    fun scan() {
        _state.update { it.copy(scanning = true, scanError = null) }
        viewModelScope.launch {
            runCatching { repository.scanDeviceAudio() to repository.deviceImportedUris() }
                .onSuccess { (found, uris) ->
                    entries = found.sortedBy { it.bookRelativePath.lowercase() }
                    _state.update {
                        it.copy(
                            scanning = false,
                            totalFound = found.size,
                            importedUris = uris,
                            listing = relisting(it.volume, it.path),
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "本机音频扫描失败", error)
                    _state.update {
                        it.copy(
                            scanning = false,
                            scanError = "扫描失败：${error.message ?: error.javaClass.simpleName}",
                            listing = DeviceAudioListing(emptyList(), emptyList()),
                        )
                    }
                }
        }
    }

    fun openFolder(ref: DeviceAudioFolderRef) {
        _state.update {
            it.copy(volume = ref.volume, path = ref.path.ifEmpty { null }, listing = relisting(ref.volume, ref.path))
        }
    }

    fun goUp() {
        _state.update { it.goUp().let { next -> next.copy(listing = relisting(next.volume, next.path)) } }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    /** 整夹导入（含子层级）；已在书架的文件自动跳过，结果一句话回给用户 */
    fun importFolder(ref: DeviceAudioFolderRef) = runBusy {
        val outcome = repository.importDeviceFolder(ref.volume, ref.path)
        refreshImportedUris()
        buildString {
            append("已导入 ${outcome.added} 首")
            if (outcome.alreadyOnShelf > 0) append("，${outcome.alreadyOnShelf} 首已在书架")
            if (outcome.revived > 0) append("，${outcome.revived} 首文件已回来")
            if (outcome.preparingWma > 0) append("，${outcome.preparingWma} 首 WMA 正在后台准备")
        }.ifBlank { null } ?: "没有可导入的音频"
    }

    /** 单文件导入：已在书架时不重复建记录，也不动同文件夹其他书 */
    fun importFile(entry: DeviceAudioEntry) = runBusy {
        val outcome = repository.importDeviceFile(entry)
        refreshImportedUris()
        when {
            outcome.added > 0 -> "已加入书架：${TitleDeriver.defaultTitle(entry.fileName)}"
            outcome.alreadyOnShelf > 0 -> null // 已在书架：静默，行上有角标
            else -> "没有变化"
        }
    }

    private suspend fun refreshImportedUris() {
        _state.update { it.copy(importedUris = repository.deviceImportedUris()) }
    }

    private fun relisting(volume: String?, path: String?): DeviceAudioListing {
        if (volume == null) {
            // 选卷层：把每个卷当成一个「文件夹」（ref 的 path 为空即卷根）；主卷排最前，其余按名排
            val folders = entries.groupingBy { it.volume }
                .eachCount()
                .map { (v, count) -> DeviceAudioListing.FolderWithCount(DeviceAudioFolderRef(v, ""), count) }
                .sortedWith(
                    compareBy(
                        { it.ref.volume != DeviceAudioFolderRef.VOLUME_EXTERNAL_PRIMARY },
                        { it.ref.displayName.lowercase() },
                    ),
                )
            return DeviceAudioListing(folders, emptyList())
        }
        return DeviceAudioIndex(entries).childrenOf(DeviceAudioFolderRef(volume, path ?: ""))
    }

    private fun runBusy(block: suspend () -> String?) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { current ->
                current.copy(
                    isBusy = false,
                    message = result.getOrElse { error ->
                        Log.w(TAG, "本机音频操作失败", error)
                        when (error) {
                            is PermissionRevokedException -> "缺少「音乐和音频」读取权限，请重新授权"
                            else -> "操作失败：${error.message ?: error.javaClass.simpleName}"
                        }
                    } ?: current.message,
                )
            }
        }
    }

    companion object {
        private const val TAG = "EchoMindDeviceAudio"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EchoMindApp
                DeviceAudioViewModel(app.graph.audioRepository)
            }
        }
    }
}
