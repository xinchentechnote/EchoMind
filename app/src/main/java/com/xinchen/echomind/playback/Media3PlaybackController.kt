package com.xinchen.echomind.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.domain.model.ListeningSessionData
import com.xinchen.echomind.domain.model.PlaybackProgress
import com.xinchen.echomind.domain.model.SessionEndReason
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.playback.QueueItem
import com.xinchen.echomind.domain.playback.SessionTracker
import com.xinchen.echomind.domain.playback.resumePositionFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Media3 实现 + 统计装饰层（docs/playback-design.md §1）。
 *
 * UI 只发命令、收 [PlaybackUiState]；这里把播放器事件翻译成 [SessionTracker] 的显式事件，
 * 再把 tracker 的快照交给 [PlaybackRepository] 落库。「播放」与「统计」因此可以各自测试。
 *
 * 线程：ExoPlayer 绑定主线程，所以本类的方法与回调都在主线程，[scope] 必须是主线程作用域。
 * 落库全部经 [writes] 单通道串行执行——切歌时「先结算旧会话再开新会话」（FR-409）由队列顺序保证，
 * 不允许 flush 插在两者中间。
 *
 * [startSessionService] 用来拉起 [EchoMindMediaService]：MediaSessionService 只在外有 controller
 * 绑定时才被系统创建，本 App 的 UI 不绑 controller，不主动 start 就没有会话注册、也没有通知栏。
 */
class Media3PlaybackController(
    private val player: ExoPlayer,
    private val repository: PlaybackRepository,
    private val clock: TimeSource,
    private val scope: CoroutineScope,
    private val startSessionService: () -> Unit,
    /** 单曲循环的初值（来自设置）与持久化出口（FR-310）；lambda 接缝让 JVM 测试不碰 SharedPreferences */
    initialRepeatOne: Boolean = false,
    private val onRepeatOneChanged: (Boolean) -> Unit = {},
) : PlaybackController, Player.Listener {

    private val tracker = SessionTracker(clock)
    private val _state = MutableStateFlow(PlaybackUiState(repeatOne = initialRepeatOne))
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var queue: List<QueueItem> = emptyList()
    private var sessionSpeed = PlaybackPolicy.DEFAULT_SPEED
    private var lastFlushMonotonic = 0L
    private var waitingForUserToResume = false

    /**
     * 最近一次播放错误**属于哪本书**。错误只跟着出错的那一首走：换到别的书（用户点开另一本、队列自动前进）
     * 就不再显示，同一本重新播成功（READY）也清掉。实测踩过：WMA 原件播不了留下的「无法播放」
     * 一直挂在后面打开的每一本正常书的播放页上。
     */
    private var erroredAudioId: String? = null

    init {
        player.addListener(this)
        player.repeatMode = if (initialRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        scope.launch {
            for (op in writes) {
                runCatching { op() }
                    .onFailure { Log.w(TAG, "playback db write failed", it) }
            }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(PlaybackPolicy.UI_TICK_INTERVAL_MS)
            }
        }
    }

    override fun play(queue: List<QueueItem>, index: Int, startPosMs: Long) {
        val item = queue.getOrNull(index) ?: return
        waitingForUserToResume = false
        startSession(item, startPosMs)
        this.queue = queue
        // 先设倍速再 setMediaItems：顺序反了 Media3 会按新 item 归一化速率（playback-design §6）
        player.setPlaybackSpeed(item.speed)
        player.setMediaItems(queue.map { it.toMediaItem() }, index, startPosMs)
        player.prepare()
        player.playWhenReady = true
        pushState()
    }

    override fun next() {
        val target = player.currentMediaItemIndex + 1
        if (target < queue.size) switchTo(target)
    }

    override fun previous() {
        val target = player.currentMediaItemIndex - 1
        if (target >= 0) switchTo(target)
    }

    override fun togglePlayPause() {
        // 判据用 isPlaying 而不是 playWhenReady：整队列播完时 Media3 仍留着 playWhenReady=true，
        // 用后者会把「播放」变成一次无效的暂停（实测）。页面按钮文案用的也是 isPlaying，两者必须同源。
        if (player.isPlaying) {
            player.pause()
            flushNow()
        } else {
            resume()
            player.playWhenReady = true
        }
        pushState()
    }

    /**
     * 从「没在播」回到「能播」：三种情形都得覆盖，实测过第一种之外的情形。
     * - 暂停后续播：位置就在原地，会话还开着，什么都不用做。
     * - 中断（焦点丢失/耳机拔出）后：会话已结算，按当前位置重开一场。
     * - 整队列播完（STATE_ENDED，甚至已被 Media3 收进 IDLE）：光设 playWhenReady 不会回头，
     *   按下去毫无反应，所以显式回到续播点（刚听完 → 从头）并重新 prepare。
     */
    private fun resume() {
        val item = currentItem() ?: return
        waitingForUserToResume = false
        val durationMs = if (player.duration > 0) player.duration else item.durationMs
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val atEnd = player.playbackState == Player.STATE_ENDED ||
            (player.playbackState == Player.STATE_IDLE) ||
            (durationMs > 0 && positionMs >= durationMs - PlaybackPolicy.END_TOLERANCE_MS)
        if (atEnd) {
            val start = resumePositionFor(durationMs, positionMs)
            if (tracker.activeAudioId != item.audioId) startSession(item, start)
            player.seekTo(player.currentMediaItemIndex, start)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        } else if (tracker.activeAudioId == null) {
            startSession(item, positionMs)
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, duration())
        tracker.onSeek(clamped)
        player.seekTo(clamped)
        flushNow()
        pushState()
    }

    override fun skip(byMs: Long) = seekTo(player.currentPosition + byMs)

    override fun setSpeed(speed: Float) {
        sessionSpeed = speed
        player.setPlaybackSpeed(speed)
        queue = queue.map { if (it.audioId == state.value.audioId) it.copy(speed = speed) else it }
        currentItem()?.let { item -> enqueue { repository.saveSpeed(item.audioId, speed) } }
        pushState()
    }

    override fun setRepeatOne(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        onRepeatOneChanged(enabled)
        pushState()
    }

    /** 通知栏/媒体键也可能改循环模式：以播放器为准同步回状态与设置 */
    override fun onRepeatModeChanged(repeatMode: Int) {
        onRepeatOneChanged(repeatMode == Player.REPEAT_MODE_ONE)
        pushState()
    }

    override fun stop() {
        val reason = if (player.playbackState == Player.STATE_ENDED) SessionEndReason.Natural else SessionEndReason.UserStopped
        settle(reason)
        player.stop()
        pushState()
    }

    // ---- Player.Listener ----

    override fun onPlaybackStateChanged(state: Int) {
        updatePlayStop()
        when (state) {
            Player.STATE_ENDED -> settle(SessionEndReason.Natural)
            Player.STATE_IDLE -> settle(SessionEndReason.UserStopped)
            Player.STATE_READY -> {
                adoptDuration()
                // 这一本已经能播了：上一次关于它的错误不再成立
                if (erroredAudioId == currentItem()?.audioId) erroredAudioId = null
            }
        }
        pushState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        updatePlayStop()
        when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> settle(SessionEndReason.FocusLost)
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> settle(SessionEndReason.BecomingNoisy)
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> if (!playWhenReady) flushNow()
        }
        pushState()
    }

    /**
     * 焦点被瞬时抢占（来电、导航播报）走的是这里，而不是 [onPlayWhenReadyChanged] 的 FOCUS_LOSS：
     * Media3 对 `AUDIOFOCUS_LOSS_TRANSIENT` 只置 suppressionReason，焦点一回来它自己把
     * playWhenReady 恢复成 true。所以这里做两件事——结算本场会话（§4），并在焦点回来时
     * **自动续播**（FR-305 的 2026-10-30 口径：被电话等中断，挂断后接着听；会话结算了就按
     * 当前位置重开一场，统计不因续播断档）。
     *
     * 只续「瞬时抢占」这一种：永久失去焦点（别的播放 App 开播）走 FOCUS_LOSS 分支不回来；
     * 耳机拔出（BECOMING_NOISY）走的是 playWhenReady 分支——外放突然出声是事故，绝不自动续。
     */
    override fun onPlaybackSuppressionReasonChanged(reason: Int) {
        if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            updatePlayStop()
            settle(SessionEndReason.FocusLost)
            waitingForUserToResume = true
            player.pause()
        } else if (waitingForUserToResume) {
            waitingForUserToResume = false
            // 焦点回来了：本场会话在中断时已结算，先按原位重开一场再续播，这段收听才有人记账
            currentItem()?.let { item ->
                if (tracker.activeAudioId == null) {
                    startSession(item, player.currentPosition.coerceAtLeast(0))
                }
            }
            player.play()
        } else {
            updatePlayStop()
        }
        pushState()
    }

    /**
     * 上一首播完自动前进、以及外部（通知栏/媒体键）切歌都会走到这里：换了书就必须先结算旧会话
     * 再为新书开一场（FR-409）。
     *
     * 实测踩过：不接这个回调，一首播完接着播下一首时两首的时间被记进同一场会话，
     * 而且会话快照的时长会被后一首的时长覆盖。
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val target = currentItem() ?: return
        if (target.audioId == tracker.activeAudioId) return
        val start = resumePositionFor(target.durationMs, target.resumePositionMs)
        startSession(target, start)
        // 倍速也是每首自己的（FR-303）：Media3 自动前进时不会替我们改速率。
        // 实测踩过：第二章设成 1.5x 播完切到长章节（记忆 1.0x），dumpsys 仍是 speed=1.5，
        // 而 sessionSpeed 已经换成 1.0——播的是 1.5 的速率，记的却是 1.0 的账。
        player.setPlaybackSpeed(target.speed)
        // 队列里的下一首也有自己的记忆位置，不能都从头播
        if (start > 0) player.seekTo(player.currentMediaItemIndex, start)
        updatePlayStop()
        pushState()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updatePlayStop()
        if (!isPlaying) flushNow()
        pushState()
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "player error", error)
        erroredAudioId = currentItem()?.audioId
        _state.update { it.copy(error = "无法播放（${error.errorCodeName}）") }
        flushNow()
    }

    // ---- 内部 ----

    private fun switchTo(index: Int) {
        val item = queue[index]
        val start = resumePositionFor(item.durationMs, item.resumePositionMs)
        startSession(item, start)
        player.setPlaybackSpeed(item.speed)
        player.seekTo(index, start)
        player.playWhenReady = true
        pushState()
    }

    /** 会话起点：结算上一首（若有）与开新会话必须在同一步里落库，否则切歌顺序无法保证（FR-409） */
    private fun startSession(item: QueueItem, startPosMs: Long) {
        // 每一场会话开始时都确保服务活着：通知栏、媒体键、车机都挂在它的 MediaSession 上
        startSessionService()
        val previousSpeed = sessionSpeed
        sessionSpeed = item.speed
        val carried = tracker.start(item.audioId, item.title, startPosMs, item.durationMs)
        lastFlushMonotonic = clock.monotonicMs()
        enqueue {
            if (carried != null) repository.settleSession(carried, previousSpeed)
            repository.openSession(item.audioId, item.title, startPosMs, item.durationMs)
        }
    }

    private fun settle(reason: SessionEndReason) {
        val session = tracker.endSession(reason) ?: return
        val speed = sessionSpeed
        lastFlushMonotonic = clock.monotonicMs()
        enqueue { repository.settleSession(session, speed) }
    }

    /**
     * 只在「真在播」时给 tracker 计时：STATE_READY 且 isPlaying 且未被抑制。
     * 缓冲、暂停、焦点丢失、playWhenReady=true 但被 Media3 压住——全都不计时（FR-402）。
     */
    private fun updatePlayStop() {
        val counting = player.isPlaying &&
            player.playbackState == Player.STATE_READY &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (counting) tracker.onPlayStarted() else {
            tracker.onPlayStopped()
        }
    }

    private fun adoptDuration() {
        val reported = player.duration
        if (reported > 0) {
            tracker.onDuration(reported)
            queue = queue.map { if (it.audioId == state.value.audioId) it.copy(durationMs = reported) else it }
        }
    }

    private fun tick() {
        if (queue.isEmpty()) return
        adoptDuration()
        updatePlayStop()
        tracker.onPosition(player.currentPosition.coerceAtLeast(0))
        val progress = tracker.progress()
        if (progress != null && clock.monotonicMs() - lastFlushMonotonic >= PlaybackPolicy.POSITION_FLUSH_INTERVAL_MS) {
            flush(progress)
        }
        pushState()
    }

    /** 状态跃迁（暂停/跳转/出错/结束）时立即落盘，不等下一个节流点（FR-412） */
    private fun flushNow() {
        tracker.progress()?.let { flush(it) }
    }

    private fun flush(progress: PlaybackProgress) {
        lastFlushMonotonic = clock.monotonicMs()
        val speed = sessionSpeed
        enqueue { repository.flushProgress(progress, speed) }
    }

    private fun enqueue(op: suspend () -> Unit) {
        val result = writes.trySend(op)
        if (result.isFailure) Log.w(TAG, "playback write channel closed")
    }

    private fun currentItem(): QueueItem? = queue.getOrNull(player.currentMediaItemIndex)

    /**
     * 带上 metadata 的 MediaItem。只给 uri 的话 Media3 拿不到标题，
     * 通知栏与锁屏就只显示「EchoMind is running」——实测过，用户看不出在播哪本。
     */
    private fun QueueItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(audioId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setDurationMs(if (durationMs > 0) durationMs else null)
                .build(),
        )
        .build()

    private fun duration(): Long {
        val fromPlayer = player.duration
        return if (fromPlayer > 0) fromPlayer else currentItem()?.durationMs ?: 0
    }

    private fun pushState() {
        val item = currentItem()
        _state.update { current ->
            current.copy(
                audioId = item?.audioId,
                title = item?.title ?: current.title,
                isPlaying = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration(),
                speed = player.playbackParameters.speed,
                queue = queue,
                index = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
                // 错误只属于出错的那一本：当前在播的不是它，就不显示
                error = if (item != null && item.audioId == erroredAudioId) current.error else null,
                repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            )
        }
    }

    private companion object {
        const val TAG = "EchoMindPlayback"
    }
}
