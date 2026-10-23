package com.xinchen.echomind.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.asr.ModelCatalog
import com.xinchen.echomind.asr.ModelCatalog.AUTO_MODEL_ID
import com.xinchen.echomind.asr.ModelCatalog.SENSEVOICE_ID
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import com.xinchen.echomind.ui.common.safeLogW
import com.xinchen.echomind.work.TranscriptWorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 转录模型的选择（D41/D42 中英文支持）。[toRef] 给出写进任务行的模型引用：
 * AUTO 由协调器在装载引擎前探测语言并改写行上的 model_id（SenseVoice 听开头 30 s 按字符系别判）。
 */
enum class ModelChoice(val label: String) {
    AUTO("自动识别"),
    ZH_BOOK("中文书解"),
    MULTILINGUAL("英文/歌曲"),
    ;

    fun toRef(): TranscriptModelRef = when (this) {
        AUTO -> TranscriptModelRef(AUTO_MODEL_ID, "")
        ZH_BOOK -> ModelCatalog.transcriptRef
        MULTILINGUAL -> TranscriptModelRef(SENSEVOICE_ID, "")
    }
}

data class TranscriptUiState(
    val jobs: List<TranscriptJobRow> = emptyList(),
    /** audioId → 书名。列表要显示书的名字，而任务行里只有 id */
    val titles: Map<String, String> = emptyMap(),
    /** audioId → 这本书现在的 ASR 字幕情况。在 VM 里从 `transcription_state` 翻成 [ExistingAsr]，页面不碰 Room 常量 */
    val existingAsr: Map<String, ExistingAsr> = emptyMap(),
    /** 从书架「转录这本书」带进来的目标；null = 只是在看列表 */
    val pendingAudioId: String? = null,
    /** 本次转录用哪个识别模型（确认卡片上可改，默认自动） */
    val modelChoice: ModelChoice = ModelChoice.AUTO,
    val isBusy: Boolean = false,
    val message: String? = null,
)

/**
 * 转录页的 ViewModel（FR-507/508）。
 *
 * 它是 `TranscriptWorkScheduler.schedule()` 的**第一个生产调用方**：之前这条路只有测试在调，
 * 所以「点一下就能转录」这句话一直没有设备证据。这里只做三件事——入队、取消、重试，
 * 状态一律回读库里那一行（库是唯一事实来源，worker 才是写入方），不在内存里自造进度。
 */
class TranscriptViewModel(
    private val repository: TranscriptRepository,
    private val audioRepository: AudioRepository,
    private val workScheduler: TranscriptWorkScheduler,
    private val model: TranscriptModelRef,
) : ViewModel() {

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.jobs().collect { rows -> _state.update { it.copy(jobs = rows) } }
        }
        viewModelScope.launch {
            audioRepository.shelf(ShelfTab.All, "").collect { rows ->
                _state.update {
                    it.copy(
                        titles = rows.associate { row -> row.id to row.title },
                        // READY 会被仓储挡住非 force 入队，EXPIRED 不挡但旧字幕同样会被整本替换：
                        // 这两件事必须在点「开始转录」之前就区分开，所以在这里翻成 UI 的三态
                        existingAsr = rows.associate { row ->
                            row.id to when (row.transcriptionState) {
                                AudioBookEntity.TranscriptionState.Ready -> ExistingAsr.Ready
                                AudioBookEntity.TranscriptionState.Expired -> ExistingAsr.Expired
                                else -> ExistingAsr.None
                            }
                        },
                    )
                }
            }
        }
    }

    /** 书架那侧带进来的目标（导航参数）。页面渲染完就可以把它清掉，免得转回来又问一次 */
    fun acceptTarget(audioId: String?) {
        if (audioId != null) _state.update { it.copy(pendingAudioId = audioId) }
    }

    fun clearTarget() {
        _state.update { it.copy(pendingAudioId = null) }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    /** 确认卡片上改模型；已入队的任务不受影响（模型在入队时定型） */
    fun onModelSelected(choice: ModelChoice) {
        _state.update { it.copy(modelChoice = choice) }
    }

    fun transcribe(audioId: String, force: Boolean) = runBusy {
        val model = _state.value.modelChoice.toRef()
        when (val outcome = repository.enqueue(audioId, model, force)) {
            is EnqueueOutcome.Created -> scheduleThen(outcome.jobId, audioId)
            is EnqueueOutcome.AlreadyActive -> TranscriptOutcomeCopy.AlreadyActive
            EnqueueOutcome.AlreadyTranscribed -> TranscriptOutcomeCopy.AlreadyTranscribed
            EnqueueOutcome.AudioNotFound -> TranscriptOutcomeCopy.BookGone
            EnqueueOutcome.JobNotFound -> TranscriptOutcomeCopy.JobGone
        }
    }

    /** 重试 = 对同一本书再入队（新行顶掉这一行，一本书一行），再把新的那一行交给 WorkManager */
    fun retry(jobId: String) = runBusy {
        val model = _state.value.modelChoice.toRef()
        when (val outcome = repository.retry(jobId, model)) {
            is EnqueueOutcome.Created -> {
                val audioId = repository.jobById(outcome.jobId)?.audioId
                    ?: return@runBusy TranscriptOutcomeCopy.BookGone
                scheduleThen(outcome.jobId, audioId)
            }

            is EnqueueOutcome.AlreadyActive -> TranscriptOutcomeCopy.AlreadyActive
            EnqueueOutcome.AlreadyTranscribed -> TranscriptOutcomeCopy.AlreadyTranscribed
            EnqueueOutcome.AudioNotFound -> TranscriptOutcomeCopy.BookGone
            EnqueueOutcome.JobNotFound -> TranscriptOutcomeCopy.JobGone
        }
    }

    /**
     * 取消：调度器那边是**先改库再 `cancelWorkById`**（顺序反了会出现「worker 已停、界面还在跑」），
     * 返回 false 说明那一行已经结算完了，这里如实说出来而不是假装取消成功。
     */
    fun cancel(jobId: String) = runBusy {
        val row = _state.value.jobs.firstOrNull { it.id == jobId } ?: return@runBusy TranscriptOutcomeCopy.JobGone
        if (workScheduler.cancel(row)) TranscriptOutcomeCopy.Cancelled else TranscriptOutcomeCopy.AlreadySettled
    }

    /** 删记录：只删已结束的行（仓储那侧对 RUNNING 拒删）；成功记录连它产出的字幕一起走，确认框已经说过了 */
    fun delete(jobId: String) = runBusy {
        if (repository.delete(jobId)) TranscriptOutcomeCopy.Deleted else TranscriptOutcomeCopy.DeleteRefused
    }

    private suspend fun scheduleThen(jobId: String, audioId: String): TranscriptOutcomeCopy {
        // 写不回 work_request_id（行已经结束）时调度器会自己撤掉刚入队的请求，这里只剩「没生效」这一句
        val scheduled = workScheduler.schedule(jobId, audioId)
        return if (scheduled) TranscriptOutcomeCopy.Scheduled else TranscriptOutcomeCopy.WorkRefused
    }

    private fun runBusy(block: suspend () -> TranscriptOutcomeCopy) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { current ->
                current.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { TranscriptDisplay.enqueueOutcomeLabel(it) },
                        onFailure = { error ->
                            // 界面只给短句，细节进 logcat，否则「授权刚给就失效」这类问题无法定位
                            safeLogW(TAG, "transcript action failed", error)
                            "操作失败：${error.message ?: error.javaClass.simpleName}"
                        },
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "EchoMindTranscriptUi"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EchoMindApp
                TranscriptViewModel(
                    repository = app.graph.transcriptRepository,
                    audioRepository = app.graph.audioRepository,
                    workScheduler = app.graph.transcriptWork,
                    model = ModelCatalog.transcriptRef,
                )
            }
        }
    }
}
