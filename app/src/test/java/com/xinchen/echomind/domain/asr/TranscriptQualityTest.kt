package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.core.conf.AudioFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转录质量闸门（`docs/asr-design.md` §8 第一行，AGENTS.md §5「不许伪造时间戳」的下游）。
 *
 * 这里判的不是「丢了几个字」而是「这轮结果还配不配叫成功」，
 * 所以边界（正好等于阈值 / 刚超）与「一块都没跑」这两端都要钉死。
 */
class TranscriptQualityTest {

    @Test
    fun `一个块都没丢当然可用`() {
        assertTrue(TranscriptQuality.isUsable(totalChunks = 6, rejectedChunks = 0))
    }

    @Test
    fun `正好卡在阈值上算可用`() {
        // 20% × 5 块 = 1 块，丢掉第 1 块还在阈值内
        assertTrue(TranscriptQuality.isUsable(totalChunks = 5, rejectedChunks = 1))
    }

    @Test
    fun `刚过阈值就不该把这轮叫成功`() {
        assertFalse(TranscriptQuality.isUsable(totalChunks = 5, rejectedChunks = 2))
    }

    @Test
    fun `一块都没跑没有证据支持成功`() {
        assertFalse("分母为 0 时不许给出「可用」的结论", TranscriptQuality.isUsable(totalChunks = 0, rejectedChunks = 0))
    }

    @Test
    fun `默认阈值就是 AudioFormats 里那个数`() {
        // UI 与文档都读同一个常量，这里防止有人把 20 抄成别处的一份
        val boundary = AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT
        assertTrue(TranscriptQuality.isUsable(totalChunks = 100, rejectedChunks = boundary))
        assertFalse(TranscriptQuality.isUsable(totalChunks = 100, rejectedChunks = boundary + 1))
    }

    @Test
    fun `大数也不许被 Int 乘法绕过去`() {
        // 一小时书解按 20 s 一块是 180 块；这里取一万个块量级，乘 100 会溢出 Int
        assertTrue(TranscriptQuality.isUsable(totalChunks = 40_000_000, rejectedChunks = 1))
        assertFalse(TranscriptQuality.isUsable(totalChunks = 40_000_000, rejectedChunks = 20_000_001))
    }

    @Test
    fun `不可能的计数直接报错而不是给个结论`() {
        // 丢的块比总块数还多 = 调用方记账错了，猜一个「可用/不可用」都是把 bug 藏起来
        val reversed = runCatching { TranscriptQuality.isUsable(totalChunks = 3, rejectedChunks = 4) }.exceptionOrNull()
        assertTrue(reversed is IllegalArgumentException)
        val negative = runCatching { TranscriptQuality.isUsable(totalChunks = -1, rejectedChunks = 0) }.exceptionOrNull()
        assertTrue(negative is IllegalArgumentException)
        val badPercent = runCatching {
            TranscriptQuality.isUsable(totalChunks = 10, rejectedChunks = 1, maxRejectedPercent = 101)
        }.exceptionOrNull()
        assertTrue(badPercent is IllegalArgumentException)
    }

    @Test
    fun `阈值可以收紧到零`() {
        assertTrue(TranscriptQuality.isUsable(totalChunks = 4, rejectedChunks = 0, maxRejectedPercent = 0))
        assertFalse(TranscriptQuality.isUsable(totalChunks = 4, rejectedChunks = 1, maxRejectedPercent = 0))
    }

    @Test
    fun `小分母上的比例不会被四舍五入蒙过去`() {
        // 3 块丢 1 块 = 33.3%，阈值 33 时不该判「可用」：整数比较（1*100 vs 33*3）没有 rounding 的余地
        assertEquals(false, TranscriptQuality.isUsable(totalChunks = 3, rejectedChunks = 1, maxRejectedPercent = 33))
        assertTrue(TranscriptQuality.isUsable(totalChunks = 3, rejectedChunks = 1, maxRejectedPercent = 34))
    }
}
