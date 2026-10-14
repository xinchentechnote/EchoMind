package com.xinchen.echomind.domain.subtitle

import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 七句窗口的纯 JVM 单测（FR-701~708）。没有 Android 依赖、没有设备，
 * 因为 `SubtitleSynchronizer` 只是「已排序列表 + 位置」的函数。
 */
class SubtitleSynchronizerTest {

    private fun segments(count: Int, eachMs: Long = 1_000, offsetMs: Long = 0) =
        List(count) { SubtitleSegment(it, offsetMs + it * eachMs, offsetMs + (it + 1) * eachMs, "第${it}句") }

    private fun finder(count: Int = 10, eachMs: Long = 1_000, radius: Int = PlaybackPolicy.SUBTITLE_WINDOW_RADIUS) =
        SubtitleSynchronizer(segments(count, eachMs), radius = radius)

    @Test
    fun `空字幕表返回空窗口而不是崩`() {
        val window = SubtitleSynchronizer(emptyList()).windowAt(5_000)
        assertTrue(window.isEmpty)
        assertEquals(-1, window.currentIndex)
        assertNull(window.current)
        assertTrue(window.before.isEmpty())
        assertTrue(window.after.isEmpty())
    }

    @Test
    fun `首句时窗口只有当前句与后三句`() {
        val window = finder().windowAt(0)
        assertEquals(0, window.currentIndex)
        assertEquals("第0句", window.current?.text)
        assertTrue(window.before.isEmpty())
        assertEquals(listOf("第1句", "第2句", "第3句"), window.after.map { it.text })
    }

    @Test
    fun `中间句前后各三句一共七句`() {
        val window = finder().windowAt(5_500)
        assertEquals(5, window.currentIndex)
        assertEquals(listOf("第2句", "第3句", "第4句"), window.before.map { it.text })
        assertEquals("第5句", window.current?.text)
        assertEquals(listOf("第6句", "第7句", "第8句"), window.after.map { it.text })
    }

    @Test
    fun `末句时后面的句子不够三句也不再补位`() {
        val window = finder(count = 10).windowAt(9_500)
        assertEquals(9, window.currentIndex)
        assertTrue(window.after.isEmpty())
        assertEquals(listOf("第6句", "第7句", "第8句"), window.before.map { it.text })
    }

    @Test
    fun `位置落在两句间隙时保持前一句为当前句`() {
        // 第 3 句 3000..4000，第 4 句 4000 开始：4100 仍在间隙之后的第 4 句里，
        // 而 3900 落在第 3 句末尾与第 4 句起点之间（这里用带真实间隙的数据更清楚）
        val gaps = listOf(
            SubtitleSegment(0, 0, 2_000, "零"),
            SubtitleSegment(1, 5_000, 7_000, "壹"),
        )
        val sync = SubtitleSynchronizer(gaps)
        assertEquals(0, sync.currentIndexAt(3_000))
        assertEquals("零", sync.windowAt(3_000).current?.text)
    }

    @Test
    fun `越过下一句起点前迟滞余量才切换`() {
        val gaps = listOf(
            SubtitleSegment(0, 0, 2_000, "零"),
            SubtitleSegment(1, 5_000, 7_000, "壹"),
        )
        val sync = SubtitleSynchronizer(gaps, gapEnterMarginMs = PlaybackPolicy.GAP_ENTER_MARGIN_MS)
        assertEquals(0, sync.currentIndexAt(4_700))            // 还差 300ms，不切
        assertEquals(1, sync.currentIndexAt(4_750))            // 越过 5000-250，切
        assertEquals(1, sync.currentIndexAt(5_000))
    }

    @Test
    fun `时间戳重叠时定位只看起点不会被 end 毛刺翻回去`() {
        val overlapping = listOf(
            SubtitleSegment(0, 0, 5_000, "零"),
            SubtitleSegment(1, 4_800, 9_000, "壹"),
        )
        val sync = SubtitleSynchronizer(overlapping, gapEnterMarginMs = 0)
        assertEquals(1, sync.currentIndexAt(4_900))
        assertEquals("壹", sync.windowAt(4_900).current?.text)
    }

    @Test
    fun `位置在首句起点之前时没有当前句`() {
        val sync = SubtitleSynchronizer(listOf(SubtitleSegment(0, 10_000, 12_000, "开场")))
        val window = sync.windowAt(1_000)
        assertTrue(window.isEmpty)
        assertNull(window.current)
        assertEquals(listOf("开场"), window.after.map { it.text })
    }

    @Test
    fun `seek 回去窗口立刻跟着位置而不是停在原句`() {
        val sync = finder()
        assertEquals(7, sync.currentIndexAt(7_500))
        assertEquals(2, sync.currentIndexAt(2_500))
    }

    @Test
    fun `窗口半径来自常量`() {
        val wide = finder(count = 10, radius = 1).windowAt(5_500)
        assertEquals(listOf("第4句"), wide.before.map { it.text })
        assertEquals(listOf("第6句"), wide.after.map { it.text })
    }

    @Test
    fun `五千句时二分与逐句扫描结论一致`() {
        val many = segments(5_000, eachMs = 400)
        val sync = SubtitleSynchronizer(many)
        // 取一批有代表性的位置：句内、句末、间隙、迟滞边界
        val probes = (0 until 5_000 step 137).flatMap { i ->
            listOf(i * 400L - 1, i * 400L, i * 400L + 200, i * 400L + 399, i * 400L + 400 - PlaybackPolicy.GAP_ENTER_MARGIN_MS)
        }.filter { it >= 0 }
        probes.forEach { pos ->
            val found = many.indexOfLast { it.startMs <= pos }
            val next = found + 1
            val expected = if (next < many.size && pos >= many[next].startMs - PlaybackPolicy.GAP_ENTER_MARGIN_MS) next else found
            assertEquals("pos=$pos", expected, sync.currentIndexAt(pos))
        }
        assertFalse(sync.windowAt(2_000_000).isEmpty)
    }
}
