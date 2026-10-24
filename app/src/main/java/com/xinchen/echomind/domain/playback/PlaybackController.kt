package com.xinchen.echomind.domain.playback

import com.xinchen.echomind.core.conf.PlaybackPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放队列里的一项（由书架行投影而来，UI 不必把 URI 塞进播放器的内部类型）。
 * [resumePositionMs] 与 [speed] 是这本书上次的进度与倍速（FR-303/309），由调用方从播放记忆带入。
 */
data class QueueItem(
    val audioId: String,
    val uri: String,
    val title: String,
    val durationMs: Long,
    val resumePositionMs: Long = 0,
    val speed: Float = PlaybackPolicy.DEFAULT_SPEED,
)

/**
 * 断点续播的起点（FR-309 + FR-407）：上次停在末尾附近说明已经听完，下次从头开始，
 * 而不是每次进来都卡在 99%。时长未知时按原位置续播。
 */
fun resumePositionFor(durationMs: Long, lastPositionMs: Long): Long = when {
    lastPositionMs < 0 -> 0
    durationMs > 0 && lastPositionMs >= durationMs - PlaybackPolicy.END_TOLERANCE_MS -> 0
    else -> lastPositionMs
}

/**
 * 播放页唯一需要的状态（FR-308）。UI 只读它，不接触 ExoPlayer（FR-301、AGENTS.md §3）。
 */
data class PlaybackUiState(
    val audioId: String? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = PlaybackPolicy.DEFAULT_SPEED,
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val error: String? = null,
    /** 单曲循环（FR-310）：开着时播完当前这一首从头再放，不前进到下一首 */
    val repeatOne: Boolean = false,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = queue.isNotEmpty() && index in 0 until queue.lastIndex
    val isIdle: Boolean get() = audioId == null
}

/**
 * 播放命令入口。实现是 `playback/Media3PlaybackController`，除它之外任何人不得碰 ExoPlayer。
 *
 * 命令必须在主线程调用（ExoPlayer 绑定在应用 looper 上）；落库是异步的，调用方不等待。
 */
interface PlaybackController {
    val state: StateFlow<PlaybackUiState>

    /** 播队列中 index 处的一首，从 startPosMs 开始；换曲时先结算上一首会话（FR-409） */
    fun play(queue: List<QueueItem>, index: Int, startPosMs: Long)

    fun togglePlayPause()
    fun seekTo(positionMs: Long)

    /** ±15s，步长来自 [PlaybackPolicy.SKIP_STEP_MS] */
    fun skip(byMs: Long)
    fun setSpeed(speed: Float)
    fun next()
    fun previous()

    /**
     * 单曲循环开/关（FR-310）。全局设置，跨书生效；统计口径不变——循环回头不是换曲，
     * 同一场会话继续累计（`docs/playback-design.md` §3 的循环注记）。
     */
    fun setRepeatOne(enabled: Boolean)

    /** 停止播放并结算会话（UserStopped）：通知栏的停止键走这条路 */
    fun stop()
}
