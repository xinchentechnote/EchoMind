package com.xinchen.echomind.domain.playback

import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.domain.model.ListeningSessionData
import com.xinchen.echomind.domain.model.PlaybackProgress
import com.xinchen.echomind.domain.model.SessionEndReason
import com.xinchen.echomind.domain.model.SessionStatus

/**
 * 收听会话状态机：统计口径的唯一实现（FR-401~408，docs/playback-design.md §3）。
 *
 * 只认显式事件 + 注入的单调时钟，不读 ExoPlayer，所以整段逻辑能在 JVM 单测里用假时钟跑完，
 * 包括「墙钟跳变不影响时长」这类在真机上几乎测不出来的性质。
 *
 * 两种时长各管一件事：
 * - `listenedMs`：播放器真在播的墙上时间，倍速不放大 → 累计收听时长与「算不算一次」。
 * - `consumedAudioMs`：音频位置正向推进之和 → 完播判定，防止拖到结尾冒充听完。
 */
class SessionTracker(private val clock: TimeSource) {

    private class Open(
        val audioId: String,
        val title: String,
        var durationMs: Long,
        val startedAtMs: Long,
        val startPositionMs: Long,
    )

    private var open: Open? = null
    private var playingSinceMs: Long? = null
    private var listenedMs: Long = 0
    private var consumedMs: Long = 0
    private var lastPositionMs: Long = 0
    private var maxPositionMs: Long = 0

    val activeAudioId: String? get() = open?.audioId

    /**
     * 开启会话。若上一首还没结算，先按 [SessionEndReason.SwitchedAudio] 结算并把它返回给调用方落库
     * ——切歌顺序由这里保证，而不是指望控制器永远记得先 end 再 start（FR-409）。
     */
    fun start(audioId: String, title: String, positionMs: Long, durationMs: Long): ListeningSessionData? {
        val carried = endSession(SessionEndReason.SwitchedAudio)
        open = Open(audioId, title, durationMs, clock.wallMs(), positionMs)
        playingSinceMs = null
        listenedMs = 0
        consumedMs = 0
        lastPositionMs = positionMs
        maxPositionMs = positionMs
        return carried
    }

    /** 幂等：Media3 会在 READY/isPlaying 变化时反复回调，重复开始会把时段算两遍 */
    fun onPlayStarted() {
        if (open == null || playingSinceMs != null) return
        playingSinceMs = clock.monotonicMs()
    }

    fun onPlayStopped() {
        val since = playingSinceMs ?: return
        listenedMs += clock.monotonicMs() - since
        playingSinceMs = null
    }

    /**
     * 书架上的时长可能是 0（导入时读取失败）或与实际不符：播放器报出真实时长后要补进来，
     * 否则这一场永远无法判完播（FR-406 的分母必须是真实时长）。
     */
    fun onDuration(durationMs: Long) {
        val session = open ?: return
        if (durationMs > 0) session.durationMs = durationMs
    }

    /** 我们自己发起的跳转（seekTo / ±15s / 点字幕）：只挪基准，不计时 */
    fun onSeek(positionMs: Long) {
        lastPositionMs = positionMs
        maxPositionMs = maxOf(maxPositionMs, positionMs)
    }

    /**
     * 周期性上报播放位置（UI tick 与 flush 都调）。
     * 正向推进且步长在 [PlaybackPolicy.MAX_COUNTED_POSITION_STEP_MS] 以内才算「真听过」；
     * 超过就当作外部跳转（通知栏拖条、Media3 插值跳变），否则一次跳跃就能把完播骗过去。
     */
    fun onPosition(positionMs: Long) {
        if (open == null) return
        val delta = positionMs - lastPositionMs
        if (delta > 0 && playingSinceMs != null && delta <= PlaybackPolicy.MAX_COUNTED_POSITION_STEP_MS) {
            consumedMs += delta
        }
        lastPositionMs = positionMs
        maxPositionMs = maxOf(maxPositionMs, positionMs)
    }

    /** 含「正在播但还没停」的那一段，用于 UI 与 flush */
    fun listenedMs(): Long {
        val since = playingSinceMs
        return if (since == null) listenedMs else listenedMs + (clock.monotonicMs() - since)
    }

    fun progress(): PlaybackProgress? {
        val session = open ?: return null
        return PlaybackProgress(
            audioId = session.audioId,
            positionMs = lastPositionMs,
            durationMs = session.durationMs,
            listenedMs = listenedMs(),
            consumedAudioMs = consumedMs,
            maxReachedPositionMs = maxPositionMs,
        )
    }

    /**
     * 播放头**当前**是否在末尾容差内（控制器据此以 [SessionEndReason.Natural] 结算）。
     * 用 lastPosition 而不是 maxPosition：拖到结尾停在那儿不等于「播完了」。
     */
    fun reachedEnd(): Boolean {
        val session = open ?: return false
        if (session.durationMs <= 0) return false
        return lastPositionMs >= session.durationMs - PlaybackPolicy.END_TOLERANCE_MS
    }

    fun endSession(reason: SessionEndReason): ListeningSessionData? {
        val session = open ?: return null
        onPlayStopped()
        open = null

        val listened = listenedMs
        val counted = listened >= PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS
        val completed = isCompleted(session.durationMs, maxPositionMs, consumedMs)
        return ListeningSessionData(
            audioId = session.audioId,
            audioTitleSnapshot = session.title,
            durationMsSnapshot = session.durationMs,
            startedAtMs = session.startedAtMs,
            endedAtMs = clock.wallMs(),
            listenedMs = listened,
            consumedAudioMs = consumedMs,
            startPositionMs = session.startPositionMs,
            endPositionMs = lastPositionMs,
            maxReachedPositionMs = maxPositionMs,
            completed = completed,
            counted = counted,
            status = statusOf(reason, counted),
        )
    }

    private fun statusOf(reason: SessionEndReason, counted: Boolean) = when {
        !counted -> SessionStatus.SkippedTooShort
        reason == SessionEndReason.ProcessRestart -> SessionStatus.Crashed
        reason == SessionEndReason.SwitchedAudio ||
            reason == SessionEndReason.FocusLost ||
            reason == SessionEndReason.BecomingNoisy -> SessionStatus.Interrupted
        else -> SessionStatus.Finished
    }

    companion object {
        /**
         * 完播 = 播放头到达末尾容差内 **且** 真实听过的推进量达到比例（FR-406/407）。
         * 时长未知（≤0）一律不判完播，只记进度。
         */
        fun isCompleted(durationMs: Long, maxPositionMs: Long, consumedAudioMs: Long): Boolean {
            if (durationMs <= 0) return false
            val reachedEnd = maxPositionMs >= durationMs - PlaybackPolicy.END_TOLERANCE_MS
            return reachedEnd &&
                consumedAudioMs >= durationMs * PlaybackPolicy.COMPLETED_CONSUMED_RATIO
        }
    }
}
