package com.xinchen.echomind.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.asr.ModelImportException
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.ui.common.safeLogW
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** 每个模型包一行，直接读 [ModelManager] 的状态流——核对结论、进度、错误都从这里来，页面不自造 */
    val models: List<ModelStatus> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
)

/**
 * 设置页「模型」一节的 ViewModel（FR-510 / FR-511 / FR-901）。
 *
 * 模型随安装包内置（FR-511）：启动时 [ModelManager.ensureProvisioned] 已把文件就位，
 * 这一页能看到状态；用户删过文件后，[restore] 从 assets 恢复——**没有**下载/导入这些网络时代的动作。
 * 这里不判断文件对不对——那是 [ModelManager] 与清单的事，本类只负责调它、把结局翻成一句文案。
 *
 * [openAsset] 由装配处给（Android 侧是 `assets.open(path)`），传字符串路径是为了让本类
 * 不依赖任何 Android 类型，恢复路径能在 JVM 里测。
 */
class SettingsViewModel(
    private val modelManager: ModelManager,
    private val openAsset: (assetPath: String) -> InputStream?,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            modelManager.statuses.collect { models -> _state.update { it.copy(models = models) } }
        }
        // 进程刚起来时 verification 全是 null（「还没核对」）；核对要算 243 MB 的哈希（一两秒），
        // 必须主动跑一次，否则页面会一直停在「还没核对」
        viewModelScope.launch { refresh() }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    /** 从安装包恢复一个包里所有不合格的文件（就绪的包点了也不会动任何文件，`provision` 是幂等的） */
    fun restore(specId: String) = runOp {
        val status = statusOf(specId) ?: return@runOp "没有这个模型包：$specId"
        modelManager.provision(status.spec.id, openAsset)
        ModelDisplay.restoreSucceededLabel(statusOf(specId) ?: return@runOp "没有这个模型包：$specId")
    }

    /** 删除。调用方（页面）必须先出示 [ModelDisplay.deleteConfirmText] 拿到确认，这里不再问一遍 */
    fun deleteConfirmed(specId: String) = runOp {
        val spec = specOf(specId) ?: return@runOp "没有这个模型包：$specId"
        modelManager.delete(spec)
        ModelDisplay.deletedLabel(statusOf(specId) ?: return@runOp "没有这个模型包：$specId")
    }

    private suspend fun refresh() {
        runCatching { modelManager.refresh() }
            .onFailure { safeLogW(TAG, "模型核对失败", it) }
    }

    private fun specOf(specId: String) = _state.value.models.firstOrNull { it.spec.id == specId }?.spec

    private fun statusOf(specId: String): ModelStatus? =
        // 操作结束后 manager 已经把核对结果写回状态流，这里读到的就是最新那份
        modelManager.statuses.value.firstOrNull { it.spec.id == specId }

    private fun runOp(block: suspend () -> String) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { current ->
                current.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            // ModelImportException 的 message 本来就是给人看的（差哪个文件、assets 缺什么），
                            // 原样给用户；其余异常只给类名，细节进 logcat
                            safeLogW(TAG, "model action failed", error)
                            when (error) {
                                is ModelImportException -> "操作失败：${error.message}"
                                else -> "操作失败：${error.message ?: error.javaClass.simpleName}"
                            }
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "EchoMindSettingsUi"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EchoMindApp
                SettingsViewModel(
                    modelManager = app.graph.modelManager,
                    openAsset = { path ->
                        runCatching { app.assets.open(path) }.getOrNull()
                    },
                )
            }
        }
    }
}
