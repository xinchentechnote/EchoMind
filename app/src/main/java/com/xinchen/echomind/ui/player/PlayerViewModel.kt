package com.xinchen.echomind.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.asr.ModelCatalog
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.data.repo.SubtitleRepository
import com.xinchen.echomind.data.repo.SubtitleSource
import com.xinchen.echomind.data.repo.SubtitleSummary
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.playback.QueueItem
import com.xinchen.echomind.domain.playback.resumePositionFor
import com.xinchen.echomind.domain.subtitle.SubtitleSynchronizer
import com.xinchen.echomind.domain.subtitle.SubtitleWindow
import com.xinchen.echomind.domain.subtitle.TestSubtitleParser
import com.xinchen.echomind.domain.transcript.LiveTranscriptStore
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import com.xinchen.echomind.ui.transcript.TranscriptDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 播放页自己需要的书信息（进度/倍速/播放态都由控制器与播放记忆提供，不重复存一份） */
data class PlayerScreenState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val favorite: Boolean = false,
    val speed: Float = PlaybackPolicy.DEFAULT_SPEED,
    val playCount: Int = 0,
    val completedCount: Int = 0,
    val listenedMsTotal: Long = 0,
)

/**
 * 播放页上的转录状态条（「首次播放实时转录并显示」）。
 *
 * [statusLine] 覆盖进行中（转录中/排队中）、[failureLine] 覆盖已失败/已取消，两者不并存；
 * 实时字幕窗口单独走 [PlayerViewModel.liveWindow]——它跟着播放位置走，更新频率与状态行不同。
 */
data class PlayerTranscriptState(
    /** 这本书有 QUEUED/RUNNING 的任务（进度行该显示） */
    val running: Boolean = false,
    val statusLine: String? = null,
    val failureLine: String? = null,
)

/**
 * 活动任务的进度行文案（纯函数，JVM 可断言）。
 * 进度那半句与转录页同一来源（[TranscriptDisplay.runningPhrase]）：分母是容器时长，
 * 容器没给时长就只报段数、不画假百分比（FR-507）。
 */
internal fun liveStatusLine(job: TranscriptJobRow?, sentenceCount: Int): String? = when {
    job == null -> null
    job.status == TranscriptJobStatus.Queued ->
        "转录排队中（同时最多转 ${AudioFormats.ASR_MAX_CONCURRENT_JOBS} 本，已满），轮到后自动开始"
    job.status == TranscriptJobStatus.Running ->
        "正在转录：${TranscriptDisplay.runningPhrase(job)} · 已出 $sentenceCount 句（转完自动入库）"

    else -> null
}

/**
 * 播放页的装配层：书架行（标题/收藏/队列）+ 播放记忆（续播位置/统计）+ 控制器（实时播放态）。
 *
 * 进入本页即起播（FR-309 的「启动后恢复上次位置」），但**同一首已在播时不重开**，
 * 否则从通知栏点回 App 会把进度扔回起点。
 *
 * 页面跟随队列：一首播完自动前进到下一首时，标题、收藏与统计都得换成正在播的那一本，
 * 否则页头停在原来那本书上、正文却是另一首（实测踩过）。路由参数 [audioId] 只决定进来时播谁。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val audioId: String,
    private val controller: PlaybackController,
    private val audioRepository: AudioRepository,
    private val playbackRepository: PlaybackRepository,
    private val subtitleRepository: SubtitleRepository,
    private val transcriptRepository: TranscriptRepository,
    private val liveStore: LiveTranscriptStore,
    /**
     * 发起一轮转录的出口（工厂里装配成「入队 + 排上 WorkManager」）。
     * 做成 lambda 是为了让「该不该自动发起」的判定能在 JVM 里测——调度器那半边要 Android 的 WorkManager。
     */
    private val startTranscription: suspend (audioId: String) -> Unit,
    /** 播放用的 URI（WMA 书换成本地转码产物 m4a；其余原样）。lambda 接缝同上，JVM 可测 */
    private val playbackUriOf: (row: ShelfRow) -> String,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerScreenState())
    val state: StateFlow<PlayerScreenState> = _state.asStateFlow()

    /** 实时播放态直接透出控制器的 StateFlow：不再有第二份 position */
    val playback: StateFlow<PlaybackUiState> = controller.state

    private val _subtitles = MutableStateFlow(SubtitleWindow.EMPTY)

    /** 七句字幕窗口（FR-701）：跟着播放位置走，只在窗口内容真的变了才发新值（FR-707） */
    val subtitles: StateFlow<SubtitleWindow> = _subtitles.asStateFlow()

    private val _subtitleSummary = MutableStateFlow(SubtitleSummary(count = 0, producedByAsr = false))

    /** 有多少句、是不是 ASR 产物（FR-705 的「如实提示不可用」靠它，不靠猜） */
    val subtitleSummary: StateFlow<SubtitleSummary> = _subtitleSummary.asStateFlow()

    private val _subtitleNotice = MutableStateFlow<String?>(null)

    /** 一次性提示（导入测试字幕的结果），显示后由 UI 调 [dismissSubtitleNotice] 清掉 */
    val subtitleNotice: StateFlow<String?> = _subtitleNotice.asStateFlow()

    private val _liveTranscript = MutableStateFlow(PlayerTranscriptState())

    /** 这本书的转录状态行（转录中/排队/失败/取消），字幕区顶部如实显示 */
    val liveTranscript: StateFlow<PlayerTranscriptState> = _liveTranscript.asStateFlow()

    private val _liveWindow = MutableStateFlow<SubtitleWindow?>(null)

    /** 转录过程中的实时字幕窗口（跟着播放位置走）；null = 没有进行中的预览，以库字幕为准 */
    val liveWindow: StateFlow<SubtitleWindow?> = _liveWindow.asStateFlow()

    private val playingId = MutableStateFlow(audioId)

    /**
     * 字幕窗口半径（当前句前后各几句）。初值是 [PlaybackPolicy.SUBTITLE_WINDOW_RADIUS]（FR-701 的七句），
     * 播放页按字幕区实际能容纳的行数双向调整——大屏上「剩下的全部是字幕」，手机屏上放不下就收小，不留空白也不裁句
     * （FR-701 注记，2026-10-24）。
     */
    private val windowRadius = MutableStateFlow(PlaybackPolicy.SUBTITLE_WINDOW_RADIUS)

    init {
        viewModelScope.launch {
            val rows = audioRepository.shelf(ShelfTab.All, "").first()
            val index = rows.indexOfFirst { it.id == audioId }
            if (index < 0) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            val row = rows[index]
            val snapshot = playbackRepository.snapshot(audioId).first()
            _state.update {
                it.copy(
                    loading = false,
                    title = row.title,
                    favorite = row.isFavorite,
                    speed = snapshot.speed,
                    playCount = snapshot.playCount,
                    completedCount = snapshot.completedCount,
                    listenedMsTotal = snapshot.listenedMsTotal,
                )
            }
            if (controller.state.value.audioId != audioId) {
                controller.play(rows.toQueue(), index, resumePositionFor(row.durationMs, snapshot.positionMs))
            }
        }
        viewModelScope.launch {
            controller.state.map { it.audioId }.filterNotNull().collect { playingId.value = it }
        }
        viewModelScope.launch {
            playingId.flatMapLatest { id ->
                combine(
                    audioRepository.shelf(ShelfTab.All, "").map { rows -> rows.firstOrNull { it.id == id } },
                    playbackRepository.snapshot(id),
                ) { book, snapshot -> book to snapshot }
            }.collect { (book, snapshot) ->
                _state.update {
                    it.copy(
                        title = book?.title ?: it.title,
                        favorite = book?.isFavorite ?: false,
                        speed = snapshot.speed,
                        playCount = snapshot.playCount,
                        completedCount = snapshot.completedCount,
                        listenedMsTotal = snapshot.listenedMsTotal,
                    )
                }
            }
        }
        viewModelScope.launch {
            playingId.flatMapLatest { id -> subtitleRepository.observeSummary(id) }
                .collect { _subtitleSummary.value = it }
        }
        // 换曲只重挂字幕流，位置仍然取控制器的实时值：两处各存一份 position 就会对不上账（AGENTS.md §4）
        // 窗口半径变了（页面量出能放更多行）也重挂同步器，位置同样不动
        viewModelScope.launch {
            playingId.flatMapLatest { id ->
                combine(subtitleRepository.observeSegments(id), windowRadius) { segments, radius ->
                    SubtitleSynchronizer(segments, radius)
                }
            }.flatMapLatest { synchronizer ->
                controller.state.map { it.positionMs }
                    .distinctUntilChanged()
                    .map { positionMs -> synchronizer.windowAt(positionMs) }
            }.distinctUntilChanged().collect { _subtitles.value = it }
        }
        // 实时预览窗口：预览句子一变就重挂同步器，位置取值与库字幕同一套（七句窗口、点句跳转都复用）
        viewModelScope.launch {
            playingId.flatMapLatest { id ->
                combine(liveStore.live.map { it[id]?.sentences }.distinctUntilChanged(), windowRadius) { s, r -> s to r }
            }.flatMapLatest { (sentences, radius) ->
                if (sentences.isNullOrEmpty()) {
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    controller.state.map { it.positionMs }
                        .distinctUntilChanged()
                        .map { positionMs -> SubtitleSynchronizer(sentences, radius).windowAt(positionMs) }
                }
            }.collect { _liveWindow.value = it }
        }
        // 转录状态行：任务行（库，唯一事实来源）+ 预览句数（内存）合成一句话
        viewModelScope.launch {
            playingId.flatMapLatest { id ->
                combine(transcriptRepository.jobs(), liveStore.live) { jobs, liveMap ->
                    val job = jobs.firstOrNull { it.audioId == id }
                    val live = liveMap[id]
                    when (job?.status) {
                        TranscriptJobStatus.Failed -> PlayerTranscriptState(
                            failureLine = "转录未完成：" + (job.failure?.let { TranscriptDisplay.failureGuide(it) }
                                ?: job.errorMessage?.let { "原始信息：$it" }
                                ?: "原因未记录"),
                        )

                        TranscriptJobStatus.Cancelled ->
                            PlayerTranscriptState(failureLine = "转录已取消。想再转可以在「转录」页重试")

                        TranscriptJobStatus.Queued, TranscriptJobStatus.Running -> PlayerTranscriptState(
                            running = job.status == TranscriptJobStatus.Running,
                            statusLine = liveStatusLine(job, live?.sentences?.size ?: 0),
                        )

                        else -> PlayerTranscriptState()
                    }
                }
            }.collect { _liveTranscript.value = it }
        }
        maybeAutoTranscribe()
    }

    fun playPause() = controller.togglePlayPause()
    fun skipForward() = controller.skip(PlaybackPolicy.SKIP_STEP_MS)
    fun skipBackward() = controller.skip(-PlaybackPolicy.SKIP_STEP_MS)
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun next() = controller.next()
    fun previous() = controller.previous()

    /** 单曲循环开/关（FR-310）：全局设置，控制器负责持久化 */
    fun toggleRepeatOne() = controller.setRepeatOne(!controller.state.value.repeatOne)

    /**
     * 页面量出字幕区能放几句就把窗口调到几句：大屏放大、手机屏放不下七句长句时收小，
     * 下限 [PlaybackPolicy.SUBTITLE_WINDOW_MIN_RADIUS]（当前句 ± 1）。只在真的变了才写，避免每次重组都重挂同步器。
     */
    fun setWindowRadius(radius: Int) {
        val clamped = radius.coerceAtLeast(PlaybackPolicy.SUBTITLE_WINDOW_MIN_RADIUS)
        if (windowRadius.value != clamped) windowRadius.value = clamped
    }

    /**
     * 一键转录（用户口径，2026-10-24）：没有字幕、也没有任务在跑的书，字幕区给一个按钮直接发起，
     * 不用绕到书架菜单再去转录页确认。入队的守卫在仓储层（已有活动任务/已转好 → 不重复）。
     */
    fun transcribeNow() {
        val id = playingId.value
        viewModelScope.launch { startTranscription(id) }
    }

    /** 停止播放并结算会话；页面留在原地，用户可以再点播放 */
    fun stop() = controller.stop()

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        controller.setSpeed(speed)
    }

    fun toggleFavorite() {
        val id = playingId.value
        viewModelScope.launch {
            audioRepository.setFavorite(id, _state.value.favorite.not())
        }
    }

    /**
     * 导入测试字幕（debug 通道，FR-604 的字幕管线验证用）。
     *
     * 时间戳**只从文件里读**：解析失败就把原因回给用户，绝不退化成按字数均分（AGENTS.md §5）。
     * 落库标 `MANUAL_TEST`，UI 据此常驻标注「非 ASR 产物」，不会被误当成模型结果。
     */
    fun importTestSubtitles(raw: String?) {
        val id = playingId.value
        viewModelScope.launch {
            _subtitleNotice.value = when {
                raw == null -> "导入失败：读不到这个文件"
                else -> when (val result = TestSubtitleParser.parse(raw)) {
                    is TestSubtitleParser.Result.Failed -> "导入失败：${result.reason}"
                    is TestSubtitleParser.Result.Ok -> {
                        val n = subtitleRepository.replace(id, result.segments, SubtitleSource.MANUAL_TEST)
                        "已导入 $n 句测试字幕（非 ASR 产物）"
                    }
                }
            }
        }
    }

    fun dismissSubtitleNotice() {
        _subtitleNotice.value = null
    }

    private fun List<ShelfRow>.toQueue(): List<QueueItem> = map {
        QueueItem(
            audioId = it.id,
            uri = playbackUriOf(it),
            title = it.title,
            durationMs = it.durationMs,
            resumePositionMs = it.lastPositionMs,
            speed = it.playbackSpeed,
        )
    }

    /**
     * 「首次播放自动转录」的发起判定：书没转过（`NONE`）、库里**一个任务行都没有**、也没有任何字幕
     * （含手工测试字幕）时才发起。三个条件各挡住一种「用户已表达过意愿」的场景：
     * 转过或取消过（任务行还在，取消过就不再自动打扰）、有测试字幕（不悄悄顶掉用户导入的东西）。
     * 入队的守卫（`AlreadyActive`/`AlreadyTranscribed`）在仓储层还有一道，这里只是不加扰动的第一道。
     */
    private fun maybeAutoTranscribe() {
        viewModelScope.launch {
            val row = audioRepository.shelf(ShelfTab.All, "").first().firstOrNull { it.id == audioId }
                ?: return@launch
            if (row.transcriptionState != AudioBookEntity.TranscriptionState.None) return@launch
            if (transcriptRepository.jobs().first().any { it.audioId == audioId }) return@launch
            if (subtitleRepository.observeSummary(audioId).first().count > 0) return@launch
            startTranscription(audioId)
        }
    }

    companion object {
        fun factory(
            app: EchoMindApp,
            audioId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    audioId,
                    app.graph.playbackController,
                    app.graph.audioRepository,
                    app.graph.playbackRepository,
                    app.graph.subtitleRepository,
                    app.graph.transcriptRepository,
                    app.graph.liveTranscriptStore,
                    startTranscription = { id ->
                        when (val outcome = app.graph.transcriptRepository.enqueue(id, ModelCatalog.transcriptRef)) {
                            is EnqueueOutcome.Created -> app.graph.transcriptWork.schedule(outcome.jobId, id)
                            // AlreadyActive / AlreadyTranscribed：有守卫挡着，静默即可——转录页能看到任务
                            else -> Unit
                        }
                    },
                    playbackUriOf = { row ->
                        app.graph.wmaStore.playbackUriFor(row.fileName, row.uri, row.id)
                    },
                )
            }
        }
    }
}
