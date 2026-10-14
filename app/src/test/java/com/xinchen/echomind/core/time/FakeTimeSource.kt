package com.xinchen.echomind.core.time

/**
 * 假时钟：统计与字幕单测用它替代 SystemClock，测试里不许出现 Thread.sleep（AGENTS.md §6）。
 * 单调钟与墙钟可以分别推进，才能测出「墙钟跳变不影响时长」这类真机上几乎测不到的性质。
 */
class FakeTimeSource(
    private var monotonic: Long = 0L,
    private var wall: Long = 1_700_000_000_000L,
) : TimeSource {
    override fun monotonicMs(): Long = monotonic

    override fun wallMs(): Long = wall

    fun advanceMonotonic(ms: Long) {
        monotonic += ms
    }

    /** 模拟用户改系统时间 / 跨时区 / NTP 校正 */
    fun jumpWall(ms: Long) {
        wall += ms
    }
}
