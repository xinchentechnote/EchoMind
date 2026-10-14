package com.xinchen.echomind.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.data.repo.SubtitleRepository
import com.xinchen.echomind.data.repo.SubtitleSource
import com.xinchen.echomind.data.repo.SubtitleSummary
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.playback.QueueItem
import com.xinchen.echomind.domain.playback.resumePositionFor
import com.xinchen.echomind.domain.subtitle.SubtitleSynchronizer
import com.xinchen.echomind.domain.subtitle.SubtitleWindow
import com.xinchen.echomind.domain.subtitle.TestSubtitleParser
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

    private val playingId = MutableStateFlow(audioId)

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
        viewModelScope.launch {
            playingId.flatMapLatest { id ->
                subtitleRepository.observeSegments(id).map { segments -> SubtitleSynchronizer(segments) }
            }.flatMapLatest { synchronizer ->
                controller.state.map { it.positionMs }
                    .distinctUntilChanged()
                    .map { positionMs -> synchronizer.windowAt(positionMs) }
            }.distinctUntilChanged().collect { _subtitles.value = it }
        }
    }

    fun playPause() = controller.togglePlayPause()
    fun skipForward() = controller.skip(PlaybackPolicy.SKIP_STEP_MS)
    fun skipBackward() = controller.skip(-PlaybackPolicy.SKIP_STEP_MS)
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun next() = controller.next()
    fun previous() = controller.previous()

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
            uri = it.uri,
            title = it.title,
            durationMs = it.durationMs,
            resumePositionMs = it.lastPositionMs,
            speed = it.playbackSpeed,
        )
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
                )
            }
        }
    }
}
