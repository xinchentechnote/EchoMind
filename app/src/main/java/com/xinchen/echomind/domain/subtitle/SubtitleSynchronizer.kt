package com.xinchen.echomind.domain.subtitle

import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.domain.model.SubtitleSegment

/**
 * 七句窗口：当前句 + 前后各 [PlaybackPolicy.SUBTITLE_WINDOW_RADIUS] 句（FR-701）。
 *
 * [currentIndex] 为 -1 表示这一句都没有（字幕表为空，或位置还在首句起点之前超过迟滞余量）。
 */
data class SubtitleWindow(
    val before: List<SubtitleSegment>,
    val current: SubtitleSegment?,
    val after: List<SubtitleSegment>,
    val currentIndex: Int,
) {
    val isEmpty: Boolean get() = currentIndex < 0

    companion object {
        /** 「一句都还没有」——ViewModel 的初始值，也是字幕表为空时 [SubtitleSynchronizer.windowAt] 的返回 */
        val EMPTY = SubtitleWindow(emptyList(), null, emptyList(), -1)
    }
}

/**
 * 播放位置 → 当前句与七句窗口。纯 Kotlin：只吃已按 `segment_index` 排序的列表，不 import 任何
 * Android/Media3/Room 类型（AGENTS.md §3），所以 5000 句的二分与迟滞都能在 JVM 单测里跑。
 *
 * 定位规则（`docs/playback-design.md` §5）：
 * - 当前句 = **最后一个 `start_ms ≤ position` 的句**。`end_ms` 不参与定位——ASR 给的 end 常有
 *   几百毫秒毛刺，用它判会在两句之间翻成「没有当前句」。
 * - 迟滞：位置落在两句之间的间隙时保持前一句；越过下一句起点前 [gapEnterMarginMs] 才切换，
 *   这样下一句在人声开始前已经就位，也不会因为时间戳毛刺在边界上来回翻。
 * - 本类**无状态**：窗口只是 (segments, position) 的函数，所以节流（FR-707「仅当前句变化才更新 UI」）
 *   由调用方对 `currentIndex` 做 distinctUntilChanged，而不是藏在算法里。
 */
class SubtitleSynchronizer(
    private val segments: List<SubtitleSegment>,
    private val radius: Int = PlaybackPolicy.SUBTITLE_WINDOW_RADIUS,
    private val gapEnterMarginMs: Long = PlaybackPolicy.GAP_ENTER_MARGIN_MS,
) {
    private val starts: LongArray = LongArray(segments.size) { segments[it].startMs }

    val size: Int get() = segments.size

    fun windowAt(positionMs: Long): SubtitleWindow {
        if (segments.isEmpty()) return SubtitleWindow.EMPTY
        val index = currentIndexAt(positionMs)
        if (index < 0) {
            // 位置还在首句起点之前（超过迟滞余量）：没有任何一句在进行，窗口里只放接下来的几句
            return SubtitleWindow(emptyList(), null, segments.subList(0, radius.coerceAtMost(segments.size)).toList(), -1)
        }
        val from = (index - radius).coerceAtLeast(0)
        val to = (index + radius + 1).coerceAtMost(segments.size)
        return SubtitleWindow(
            before = segments.subList(from, index).toList(),
            current = segments[index],
            after = segments.subList(index + 1, to).toList(),
            currentIndex = index,
        )
    }

    /** 位置对应的当前句下标；-1 表示还没有句开始 */
    fun currentIndexAt(positionMs: Long): Int {
        val found = lastStartNotAfter(positionMs)
        val next = found + 1
        return if (next < starts.size && positionMs >= starts[next] - gapEnterMarginMs) next else found
    }

    /** 二分：最后一个 `start ≤ position` 的下标，全都在后面时返回 -1 */
    private fun lastStartNotAfter(positionMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
