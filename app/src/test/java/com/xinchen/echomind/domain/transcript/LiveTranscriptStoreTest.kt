package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.SubtitleSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时预览字幕的内存账本：协调器在 worker 的 IO 线程上追加、播放页读流。
 * 钉住四条性质：乱序追加按时间归位重编号、多本书互不串、clear 干净退场、
 * 「识别了但没出字」也要把段数推进去（进度不许停在旧数字上）。
 */
class LiveTranscriptStoreTest {

    private fun sentence(index: Int, startMs: Long, text: String) =
        SubtitleSegment(index = index, startMs = startMs, endMs = startMs + 500, text = text)

    @Test
    fun `追加按时间归位并重编号`() {
        val store = LiveTranscriptStore()
        store.append("a", listOf(sentence(0, 2_000, "乙")), doneSegments = 1)
        store.append("a", listOf(sentence(0, 1_000, "甲")), doneSegments = 2)

        val live = store.live.value.getValue("a")
        assertEquals(listOf("甲", "乙"), live.sentences.map { it.text })
        assertEquals(listOf(0, 1), live.sentences.map { it.index })
        assertEquals(2, live.doneSegments)
    }

    @Test
    fun `整段没有可用时间也推进段计数`() {
        val store = LiveTranscriptStore()
        store.append("a", listOf(sentence(0, 1_000, "甲")), doneSegments = 1)
        store.append("a", emptyList(), doneSegments = 2)

        val live = store.live.value.getValue("a")
        assertEquals(1, live.sentences.size)
        assertEquals(2, live.doneSegments)
    }

    @Test
    fun `多本书互不串`() {
        val store = LiveTranscriptStore()
        store.append("a", listOf(sentence(0, 1_000, "甲")), doneSegments = 1)
        store.append("b", listOf(sentence(0, 3_000, "丙")), doneSegments = 5)

        assertEquals(listOf("甲"), store.live.value.getValue("a").sentences.map { it.text })
        assertEquals(listOf("丙"), store.live.value.getValue("b").sentences.map { it.text })
        assertEquals(1, store.live.value.getValue("a").doneSegments)
        assertEquals(5, store.live.value.getValue("b").doneSegments)
    }

    @Test
    fun `清掉后没有条目也不影响别的书`() {
        val store = LiveTranscriptStore()
        store.append("a", listOf(sentence(0, 1_000, "甲")), doneSegments = 1)
        store.append("b", listOf(sentence(0, 3_000, "丙")), doneSegments = 1)

        store.clear("a")

        assertFalse(store.live.value.containsKey("a"))
        assertTrue(store.live.value.containsKey("b"))
    }

    @Test
    fun `清掉不存在的书是无操作`() {
        val store = LiveTranscriptStore()
        store.clear("ghost")
        assertTrue(store.live.value.isEmpty())
    }

    @Test
    fun `流能被订阅者观察到`() = runTest {
        val store = LiveTranscriptStore()
        store.append("a", listOf(sentence(0, 1_000, "甲")), doneSegments = 1)
        val live = store.live.first()
        assertEquals(1, live.getValue("a").sentences.size)
        store.clear("a")
        assertNull(store.live.first()["a"])
    }
}
