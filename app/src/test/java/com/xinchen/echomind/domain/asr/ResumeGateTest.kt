package com.xinchen.echomind.domain.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 续跑闸门（FR-512）的三种情形：整块丢弃、骑线掐头、原样放行。 */
class ResumeGateTest {

    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertTrue("样本内容不一致：${expected.toList()} vs ${actual.toList()}", expected.contentEquals(actual))
    }

    @Test
    fun `整块在断点之前就整个丢弃`() {
        val samples = FloatArray(100) { it.toFloat() }
        assertNull("终点 100 ≤ 断点 100 的块不该再喂 VAD", ResumeGate.slice(samples, startSample = 0, resumeFromSample = 100))
        assertNull(ResumeGate.slice(samples, startSample = 0, resumeFromSample = 1_000))
    }

    @Test
    fun `骑在断点上的块掐头保尾且起点抬到断点`() {
        val samples = FloatArray(100) { it.toFloat() }
        val trimmed = ResumeGate.slice(samples, startSample = 50, resumeFromSample = 120)
            ?: error("终点 150 > 断点 120 的块不该被丢")
        assertEquals(120L, trimmed.startSample)
        assertEquals(30, trimmed.samples.size)
        // 掐掉的是前 70 个样本（50..119），留下的是第 120 个起的原值——不重排不补零
        assertFloatArrayEquals(samples.copyOfRange(70, 100), trimmed.samples)
    }

    @Test
    fun `断点之后的块原样放行`() {
        val samples = FloatArray(100) { it.toFloat() }
        val passed = ResumeGate.slice(samples, startSample = 200, resumeFromSample = 100)
            ?: error("完全在断点之后的块不该被丢")
        assertEquals(200L, passed.startSample)
        assertFloatArrayEquals(samples, passed.samples)
        // 起点恰好在断点上：一块不多剪
        val atBoundary = ResumeGate.slice(samples, startSample = 100, resumeFromSample = 100)!!
        assertEquals(100L, atBoundary.startSample)
        assertEquals(100, atBoundary.samples.size)
    }

    @Test
    fun `空样本不炸且按位置判定`() {
        assertNull(ResumeGate.slice(FloatArray(0), startSample = 0, resumeFromSample = 0))
        val passed = ResumeGate.slice(FloatArray(0), startSample = 500, resumeFromSample = 100)
        assertNotNull(passed)
        assertEquals(500L, passed!!.startSample)
    }
}
