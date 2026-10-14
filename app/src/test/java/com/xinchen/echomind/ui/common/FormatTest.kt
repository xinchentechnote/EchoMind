package com.xinchen.echomind.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书架上的时长文案：读不到时长必须显示「时长未知」而不是 0:00（FR-104 的失败分支）。
 */
class FormatTest {

    @Test
    fun `时长未知与常规格式`() {
        assertEquals("时长未知", formatDuration(0))
        assertEquals("时长未知", formatDuration(-1))
        assertEquals("0:59", formatDuration(59_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `累计收听时长的中文口径`() {
        assertEquals("0 分钟", formatListenDuration(0))
        assertEquals("59 分钟", formatListenDuration(59 * 60_000))
        assertEquals("1 小时 1 分钟", formatListenDuration(3_660_000))
    }

    @Test
    fun `相对时间覆盖各档边界`() {
        val now = 1_760_000_000_000L
        assertEquals("未播放", formatRelativeTime(0, now))
        assertEquals("刚刚", formatRelativeTime(now - 30_000, now))
        assertEquals("5 分钟前", formatRelativeTime(now - 5 * 60_000, now))
        assertEquals("2 小时前", formatRelativeTime(now - 2 * 3_600_000, now))
        assertEquals("3 天前", formatRelativeTime(now - 3 * 86_400_000L, now))
        assertEquals("2 个月前", formatRelativeTime(now - 60 * 86_400_000L, now))
        assertEquals("1 年前", formatRelativeTime(now - 400 * 86_400_000L, now))
    }

    /** 未来时间戳（设备改过时钟）不能算出负数差值 */
    @Test
    fun `时间戳晚于当前时间时按刚刚处理`() {
        assertEquals("刚刚", formatRelativeTime(2_000_000_000L, 1_000_000_000L))
    }

    @Test
    fun `进度比例在时长未知时返回空`() {
        assertNull(progressFraction(1000, 0))
        assertEquals(0.5f, progressFraction(5_000, 10_000))
        assertEquals(0f, progressFraction(-5_000, 10_000))
        assertEquals(1f, progressFraction(20_000, 10_000))
    }
}
