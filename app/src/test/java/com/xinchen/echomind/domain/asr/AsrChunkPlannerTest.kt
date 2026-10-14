package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.SpeechSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段与绝对偏移（FR-504、FR-505）。核心性质是「不漂移」：
 * 第 N 块的时间只能由它的**全局样本下标**算出来，不能由前一块的时间加时长推出来。
 */
class AsrChunkPlannerTest {

    private val rate = AudioFormats.ASR_SAMPLE_RATE // 16_000

    private fun samples(seconds: Double) = (seconds * rate).toLong()

    private fun spanAt(startSeconds: Double, durationSeconds: Double) =
        SpeechSpan(startSample = samples(startSeconds), sampleCount = samples(durationSeconds).toInt())

    @Test
    fun `短于上限的语音段原样一块`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(10.0, 5.0)))
        assertEquals(1, plans.size)
        assertEquals(samples(10.0), plans[0].startSample)
        assertEquals(samples(5.0).toInt(), plans[0].sampleCount)
        assertEquals(10_000L, plans[0].startMs)
        assertEquals(15_000L, plans[0].endMs)
    }

    @Test
    fun `恰好等于上限不算超长`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(0.0, 20.0)))
        assertEquals(1, plans.size)
        assertEquals(20_000L, plans[0].endMs)
    }

    @Test
    fun `25秒段拆成两块且都不超过20秒`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(0.0, 25.0)))
        assertEquals(2, plans.size)
        plans.forEach { assertTrue("单块 ${(it.sampleCount * 1000L / rate)}ms", it.sampleCount.toLong() * 1000L / rate <= 20_000L) }
        assertEquals(0L, plans[0].startMs)
        assertEquals(plans[0].startSample + plans[0].sampleCount, plans[1].startSample)
        assertEquals(25_000L, plans[1].endMs)
    }

    @Test
    fun `拆分不留碎片：各块长度最多差一个样本`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(0.0, 25.0)))
        val counts = plans.map { it.sampleCount }
        assertTrue("实测 $counts", counts.max() - counts.min() <= 1)
    }

    @Test
    fun `61秒段拆成四块覆盖完整且无重叠`() {
        val span = spanAt(7.5, 61.0)
        val plans = AsrChunkPlanner.plan(listOf(span))
        assertEquals(4, plans.size)
        assertEquals(span.sampleCount.toLong(), plans.sumOf { it.sampleCount.toLong() })
        plans.zipWithNext().forEach { (a, b) ->
            assertEquals(a.startSample + a.sampleCount, b.startSample)
        }
        assertEquals(span.startSample, plans.first().startSample)
        assertEquals(span.endSample, plans.last().startSample + plans.last().sampleCount)
    }

    @Test
    fun `第N块的时间从全局样本下标算，不从0重来（FR-505）`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(1_234.5, 45.0)))
        assertTrue(plans.size >= 3)
        assertEquals(1_234_500L, plans[0].startMs)
        // 手工对照：第二块起点 = (1234.5s + 第一块长度) 对应的毫秒
        val expectedSecondStartMs = AsrChunkPlanner.msOf(plans[1].startSample, rate)
        assertEquals(expectedSecondStartMs, plans[1].startMs)
        assertTrue("第二块必须晚于第一块", plans[1].startMs > plans[0].startMs)
    }

    @Test
    fun `一万段连续长音频累计一小时也不漂移`() {
        // 每段 20s，紧挨着排：一小时正好 180 段。逐段累加余数的写法在这里就会露出偏差
        val spans = (0 until 180).map { SpeechSpan(startSample = it.toLong() * 20 * rate, sampleCount = 20 * rate) }
        val plans = AsrChunkPlanner.plan(spans)
        assertEquals(180, plans.size)
        assertEquals(3_600_000L, plans.last().endMs)
        assertEquals(60L * 60L * 1_000L, plans.last().endMs)
    }

    @Test
    fun `多个语音段按顺序产出且互不合并`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(0.0, 3.0), spanAt(30.0, 2.0), spanAt(60.0, 1.0)))
        assertEquals(3, plans.size)
        assertEquals(listOf(0L, 30_000L, 60_000L), plans.map { it.startMs })
    }

    @Test
    fun `空段与负起点的段被跳过而不是产出零样本块`() {
        val plans = AsrChunkPlanner.plan(
            listOf(
                SpeechSpan(startSample = 0, sampleCount = 0),
                SpeechSpan(startSample = 100, sampleCount = -5),
                SpeechSpan(startSample = -1, sampleCount = rate),
                SpeechSpan(startSample = 0, sampleCount = 160),
            ),
        )
        assertEquals(1, plans.size)
        assertEquals(160, plans[0].sampleCount)
    }

    @Test
    fun `空输入给空输出`() {
        assertEquals(0, AsrChunkPlanner.plan(emptyList()).size)
    }

    @Test
    fun `毫秒与样本互算向下取整`() {
        assertEquals(16_000L, AsrChunkPlanner.samplesOf(1_000L, rate))
        assertEquals(1_000L, AsrChunkPlanner.msOf(16_000L, rate))
        // 16k 下一个毫秒是 16 个样本；15 个样本不足一毫秒只能向下取整成 0，四舍五入会让每块都提前一样本多
        assertEquals(0L, AsrChunkPlanner.msOf(15L, rate))
        assertEquals(1L, AsrChunkPlanner.msOf(16L, rate))
        assertEquals(0L, AsrChunkPlanner.samplesOf(0L, 44_100))
        // 44100 个样本在 44.1kHz 下正好一秒
        assertEquals(1_000L, AsrChunkPlanner.msOf(44_100L, 44_100))
    }

    @Test
    fun `44100源采样率下的偏移同样按样本算`() {
        val plans = AsrChunkPlanner.plan(
            listOf(SpeechSpan(startSample = 44_100, sampleCount = 44_100)),
            sampleRate = 44_100,
        )
        assertEquals(1_000L, plans.single().startMs)
        assertEquals(2_000L, plans.single().endMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `单块上限小到不足一个样本时直接拒绝`() {
        AsrChunkPlanner.plan(listOf(spanAt(0.0, 1.0)), sampleRate = rate, maxChunkMs = 0L)
    }

    @Test
    fun `自定义上限生效`() {
        val plans = AsrChunkPlanner.plan(listOf(spanAt(0.0, 10.0)), sampleRate = rate, maxChunkMs = 3_000L)
        assertEquals(4, plans.size)
        plans.forEach { assertTrue(it.sampleCount <= 3 * rate) }
    }
}
