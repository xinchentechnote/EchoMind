package com.xinchen.echomind.domain.model

/**
 * 一句字幕（纯 Kotlin 投影，UI 与算法都不碰 Room 实体）。
 *
 * [index] 就是 `segment_index`：时间序 = 索引序，所以二分可以只看这个顺序，
 * 不需要额外排序，也不需要 `end_ms` 参与定位。
 */
data class SubtitleSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
