package com.xinchen.echomind.domain.subtitle

import com.xinchen.echomind.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 测试字幕解析器（AGENTS.md §5 的守门人）。
 *
 * 这里最要紧的不是「能解析多少种格式」，而是**解析不出时间时必须失败**：
 * 一旦允许兜底（缺 end 就拿上一句的 end、格式不对就均分），这个调试通道就变成了伪造字幕的入口，
 * 而它是 Phase 6 之前唯一能填满字幕表的路径。
 */
class TestSubtitleParserTest {

    private fun ok(raw: String): List<SubtitleSegment> {
        val result = TestSubtitleParser.parse(raw)
        if (result is TestSubtitleParser.Result.Failed) fail("应当解析成功，实际失败：${result.reason}")
        return (result as TestSubtitleParser.Result.Ok).segments
    }

    private fun failed(raw: String): String {
        val result = TestSubtitleParser.parse(raw)
        if (result is TestSubtitleParser.Result.Ok) fail("应当失败，实际解析出 ${result.segments.size} 句")
        return (result as TestSubtitleParser.Result.Failed).reason
    }

    /** 只比时间与文本：`index` 由解析器按时间重编，单独在乱序那条里验 */
    private fun cue(startMs: Long, endMs: Long, text: String) = Triple(startMs, endMs, text)

    private fun bodies(raw: String): List<Triple<Long, Long, String>> =
        ok(raw).map { Triple(it.startMs, it.endMs, it.text) }

    @Test
    fun `SRT 的时间与正文按文件原样读出`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            第一句

            2
            00:00:03,500 --> 00:00:06,000
            第二句
            跨了两行

            3
            00:01:00,000 --> 00:01:02,000
            第三句
        """.trimIndent()
        assertEquals(
            listOf(cue(1_000, 3_500, "第一句"), cue(3_500, 6_000, "第二句 跨了两行"), cue(60_000, 62_000, "第三句")),
            bodies(srt),
        )
    }

    @Test
    fun `VTT 去掉头与标签并容忍尾随设置`() {
        val vtt = "WEBVTT\n\n00:00:00.250 --> 00:00:01.000 align:start position:0%\n<c.yellow>带标签</c>的一句\n"
        assertEquals(listOf(cue(250, 1_000, "带标签的一句")), bodies(vtt))
    }

    @Test
    fun `缺小时的分钟格式与一位小数毫秒`() {
        // ".5" 是 500ms，不是 5ms：补足三位是 SRT/VTT 的写法，不是猜
        assertEquals(listOf(cue(0, 500, "甲")), bodies("00:00.0 --> 00:00.5\n甲\n"))
        assertEquals(listOf(cue(65_500, 66_000, "乙")), bodies("01:05.500 --> 01:06.000\n乙\n"))
    }

    @Test
    fun `TSV 三列直接是毫秒`() {
        val tsv = "# 手工对齐的测试字幕\n0\t1200\t开场\n1200\t2500\t第二段\n"
        assertEquals(listOf(cue(0, 1_200, "开场"), cue(1_200, 2_500, "第二段")), bodies(tsv))
    }

    @Test
    fun `正文里再出现分隔符仍然是一句`() {
        assertEquals(listOf(cue(0, 1_000, "有;分号的句子")), bodies("0;1000;有;分号的句子"))
    }

    @Test
    fun `乱序的行按时间重排并重新编号`() {
        val segments = ok(
            "1\n00:00:10,000 --> 00:00:12,000\n后一句\n\n2\n00:00:01,000 --> 00:00:02,000\n前一句\n",
        )
        assertEquals(listOf(0, 1), segments.map { it.index })
        assertEquals(listOf(1_000L, 10_000L), segments.map { it.startMs })
        assertEquals(listOf("前一句", "后一句"), segments.map { it.text })
    }

    @Test
    fun `时间戳解析不出来就整份拒绝而不是补零`() {
        assertTrue(failed("1\nabc --> def\n句子\n").contains("第 2 行"))
        assertTrue(failed("1\n00:00:01,000 --> \n句子\n").contains("第 2 行"))
        assertTrue(failed("0\tx\t句子").contains("结束时间"))
    }

    @Test
    fun `结束时间不晚于开始时间视为损坏`() {
        assertTrue(failed("1\n00:00:05,000 --> 00:00:05,000\n句子\n").contains("不晚于"))
        assertTrue(failed("5000\t3000\t句子").contains("不晚于"))
    }

    @Test
    fun `空文件与没有任何时间的内容都失败`() {
        assertTrue(failed("   \n").contains("空的"))
        assertTrue(failed("没有时间的纯文本\n第二行\n").contains("三列"))
        assertTrue(failed("WEBVTT\n\nNOTE 只是注释\n").contains("没有解析出"))
    }
}
