package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.domain.model.PcmChunkPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字时间换算（FR-504 的核心算术）。
 *
 * 输入形状取自 2026-10-15 emulator-5554 上第一次真推理的日志（13 token / 13 timestamp、
 * 单位秒、`durations` 为空，见 `docs/testing.md` §4），但断言本身是纯逻辑，不需要模型。
 * 断言用的秒数一律挑**二进制能精确表示**的（6.5 / 12.25 / 19.5 / 0.5），
 * 免得把 float 舍入的运气当成契约。
 */
class TokenTimingMapperTest {

    private fun block(startMs: Long = 0, endMs: Long = startMs + 20_000) =
        PcmChunkPlan(
            startSample = startMs * 16L,
            sampleCount = ((endMs - startMs) * 16L).toInt(),
            startMs = startMs,
            endMs = endMs,
        )

    private fun map(
        tokens: List<String>,
        seconds: FloatArray,
        chunk: PcmChunkPlan = block(),
    ) = TokenTimingMapper.map(tokens, seconds, chunk)

    @Test
    fun `秒换算成块内偏移再叠上这块的绝对起点`() {
        val out = map(listOf("那", "片", "笑"), floatArrayOf(6.5f, 12.25f, 19.5f), block(startMs = 20_000))

        assertEquals(listOf(26_500L, 32_250L, 39_500L), out.map { it.startMs })
        assertEquals(listOf("那", "片", "笑"), out.map { it.token })
    }

    @Test
    fun `每个字的结束就是下一个字的开始最后一个字收到块末尾`() {
        val out = map(listOf("a", "b", "c"), floatArrayOf(1f, 2.5f, 4f), block(endMs = 5_000))

        assertEquals(1_000L to 2_500L, out[0].startMs to out[0].endMs)
        assertEquals(2_500L to 4_000L, out[1].startMs to out[1].endMs)
        // 模型没给最后一个字的时长（durations 实测长度 0），只能收到这块音频的末尾
        assertEquals(4_000L to 5_000L, out[2].startMs to out[2].endMs)
    }

    @Test
    fun `取整只向下误差最多一毫秒`() {
        val out = map(listOf("近", "三"), floatArrayOf(0.999999f, 2.9999f))

        // 2.9999s 是 2999.9ms：向下截断成 2999，不许四舍五入到 3000（宁可早一毫秒也不许晚）
        assertEquals(999L, out[0].startMs)
        assertEquals(2_999L, out[1].startMs)
    }

    @Test
    fun `设备实测那种 13 秒级时间戳落在合理范围内`() {
        // 第一次真推理的第一个 timestamp 是 13.599999f；float 本身比 13.6 大还是小不确定，
        // 所以只断言「换算结果与 13600 相差不到 1 ms」，不把 float 舍入写死成契约
        val out = map(listOf("那"), floatArrayOf(13.599999f), block(startMs = 40_000))

        assertTrue("实际值：${out[0].startMs}", out[0].startMs in 53_599L..53_600L)
    }

    @Test
    fun `token 与时间戳都是空的合法块就是纯静音`() {
        assertTrue(map(emptyList(), FloatArray(0)).isEmpty())
    }

    @Test
    fun `数量不等要抛错不许裁剪也不许补齐`() {
        val tooFew = runCatching { map(listOf("a", "b", "c"), floatArrayOf(1f, 2f)) }
        assertTrue(tooFew.exceptionOrNull() is TokenTimingMapper.Mismatch)
        assertEquals(
            "token 数与时间戳数不等：tokens=3 timestamps=2（块 0~20000 ms），拒绝猜测缺的是哪一个",
            tooFew.exceptionOrNull()?.message,
        )

        val tooMany = runCatching { map(listOf("a"), floatArrayOf(1f, 2f, 3f)) }
        assertTrue(tooMany.exceptionOrNull() is TokenTimingMapper.Mismatch)
    }

    @Test
    fun `只有 token 没有时间也要抛错那是模型没给时间不许造`() {
        val outcome = runCatching { map(listOf("字"), FloatArray(0)) }
        assertTrue(outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
    }

    @Test
    fun `时间戳为负要抛错`() {
        val outcome = runCatching { map(listOf("a", "b"), floatArrayOf(-0.02f, 1f)) }
        assertTrue(outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
        assertEquals("第 0 个字「a」的时间戳为负：-0.02s", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `非有限数值要抛错`() {
        for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val outcome = runCatching { map(listOf("a"), floatArrayOf(bad)) }
            assertTrue("$bad 应该被判为非有限数", outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
        }
    }

    @Test
    fun `落在块外要抛错哪怕只超半秒`() {
        // 这块只有 5s，模型说第 6.5s 有字 → 喂进去的样本数和这块对不上，绝不能夹回 5000
        val outcome = runCatching { map(listOf("超"), floatArrayOf(6.5f), block(endMs = 5_000)) }
        assertTrue(outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
        assertEquals(
            "第 0 个字「超」落在块外：6.5s = 块内第 6500 ms，而这块只有 5000 ms（0~5000 ms）",
            outcome.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `正好压在块尾是合法的`() {
        val out = map(listOf("尾"), floatArrayOf(5f), block(endMs = 5_000))

        assertEquals(5_000L to 5_000L, out[0].startMs to out[0].endMs)
    }

    @Test
    fun `时间戳倒序要抛错`() {
        val outcome = runCatching { map(listOf("a", "b"), floatArrayOf(3f, 1f)) }
        assertTrue(outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
        assertEquals("时间戳倒序：第 0 个字在 3000 ms，第 1 个字却在 1000 ms", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `相邻两字挤在同一毫秒是合法的零长token`() {
        // 截断后会出现 endMs == startMs 的字：这是模型精度的下限，不许为此造时间
        val out = map(listOf("快", "也", "慢"), floatArrayOf(1f, 1.0004f, 2f), block(endMs = 3_000))

        assertEquals(1_000L to 1_000L, out[0].startMs to out[0].endMs)
        assertEquals(1_000L to 2_000L, out[1].startMs to out[1].endMs)
    }

    @Test
    fun `任意非降序输入都不会造出倒挂的起止`() {
        val seconds = FloatArray(50) { it * 0.39f }
        val out = map((0 until 50).map { "字$it" }, seconds, block(endMs = 20_000))

        assertEquals(50, out.size)
        assertTrue(out.all { it.endMs >= it.startMs })
        assertEquals(20_000L, out.maxOf { it.endMs })
        assertTrue(out.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
    }
}
