package com.xinchen.echomind.domain.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样本时间轴锚到 PTS 的账本：干净流零动作、丢帧垫静音、PTS 倒退重锚、容差内不抖。 */
class PtsTimelineTest {

    private val rate = 16_000

    @Test
    fun `首个pts定起点不插静音`() {
        val t = PtsTimeline()
        assertEquals(0L, t.gapBefore(actualPtsUs = 500_000L, streamIndex = 0, sampleRate = rate))
        // 起点 = 500_000 - 0；继续推进
        assertEquals(0L, t.gapBefore(actualPtsUs = 500_000L + 100_000L, streamIndex = 1_600, sampleRate = rate))
        assertTrue(t.isAnchored)
    }

    @Test
    fun `干净流全程零插入`() {
        val t = PtsTimeline()
        var samples = 0L
        var pts = 123_456L // 故意非零起点
        repeat(100) {
            assertEquals(0L, t.gapBefore(pts, samples, rate))
            samples += 1_600 // 100ms
            pts += 100_000
        }
    }

    @Test
    fun `垫完静音后期望位置顺延不重复垫`() {
        val t = PtsTimeline()
        t.gapBefore(0L, 0L, rate)
        // 流推进到 1s（16000 样本），PTS 跳到 1.5s（丢了 0.5s 的帧）
        val gap = t.gapBefore(actualPtsUs = 1_500_000L, streamIndex = 16_000L, sampleRate = rate)
        assertEquals(500_000L, gap)
        // 垫了 8000 样本（0.5s 静音）之后，流内序号 24000 应该正好落在 PTS 1.5s；
        // 再往后 1000 个样本（62.5ms）仍然在容差内
        assertEquals(0L, t.gapBefore(actualPtsUs = 1_500_000L, streamIndex = 24_000L, sampleRate = rate))
        assertEquals(0L, t.gapBefore(actualPtsUs = 1_562_500L, streamIndex = 25_000L, sampleRate = rate))
    }

    @Test
    fun `容差内的抖动不动作`() {
        val t = PtsTimeline()
        t.gapBefore(0L, 0L, rate)
        assertEquals(0L, t.gapBefore(actualPtsUs = 15_000L, streamIndex = 0L, sampleRate = rate))
        assertEquals(0L, t.gapBefore(actualPtsUs = 5_000L, streamIndex = 160L, sampleRate = rate))
    }

    @Test
    fun `pts倒退重锚不插静音也不搬历史`() {
        val t = PtsTimeline()
        t.gapBefore(1_000_000L, 16_000L, rate) // 起点 = 1_000_000 - 1s = 0
        assertEquals(0L, t.gapBefore(actualPtsUs = 900_000L, streamIndex = 32_000L, sampleRate = rate))
        // 重锚后起点变了：下一次按新起点算
        assertEquals(0L, t.gapBefore(actualPtsUs = 1_100_000L, streamIndex = 48_000L, sampleRate = rate))
    }

    @Test
    fun `负pts不可信时维持样本计数`() {
        val t = PtsTimeline()
        assertEquals(0L, t.gapBefore(actualPtsUs = -1L, streamIndex = 16_000L, sampleRate = rate))
        assertFalse(t.isAnchored)
    }
}
