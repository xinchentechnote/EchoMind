package com.xinchen.echomind.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ListeningSessionEntity
import com.xinchen.echomind.domain.model.SubtitleSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 字幕落库的仪器化测试（真 SQLite：事务、`segment_index` 唯一索引、外键级联都得真跑，JVM 单测覆盖不到）。
 *
 * 三条产品约束都在这里对账：
 * - FR-601 时间序 = 索引序，且索引连续；
 * - FR-605 替换是整本的：第二次导入之后不能留任何上一本的残句；
 * - NFR-04 删书连带删字幕，但**不**删收听会话。
 */
@RunWith(AndroidJUnit4::class)
class SubtitleRepositoryTest {

    private class FakeClock : TimeSource {
        var wall = 1_700_000_000_000L
        override fun monotonicMs(): Long = wall
        override fun wallMs(): Long = wall
    }

    private lateinit var db: EchoMindDatabase
    private lateinit var repo: SubtitleRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EchoMindDatabase::class.java,
        ).build()
        repo = SubtitleRepositoryImpl(db, FakeClock())
        db.importedFolderDao().upsert(
            ImportedFolderEntity(id = "folder-1", treeUri = "tree://music", displayName = "Music", grantedAtMs = 1L),
        )
        db.audioBookDao().upsert(
            AudioBookEntity(
                id = "a",
                uri = "uri://a",
                folderId = "folder-1",
                relativePath = "a.mp3",
                fileName = "a.mp3",
                title = "第一首",
                sizeBytes = 1_000,
                fileLastModifiedMs = 1,
                addedAtMs = 1L,
                lastScannedAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun segment(index: Int, startMs: Long, text: String) =
        SubtitleSegment(index = index, startMs = startMs, endMs = startMs + 1_000, text = text)

    @Test
    fun 导入后按索引顺序读回且索引连续() = runBlocking {
        val written = repo.replace("a", listOf(segment(0, 4_000, "后"), segment(1, 0, "先"), segment(2, 2_000, "中")), SubtitleSource.MANUAL_TEST)
        assertEquals(3, written)

        val read = db.subtitleSegmentDao().getByAudio("a")
        assertEquals(listOf(0, 1, 2), read.map { it.segmentIndex })
        // 仓库按传入顺序编号，不做二次排序：顺序是调用方（解析器已按时间排好）的责任
        assertEquals(listOf("后", "先", "中"), read.map { it.text })
        assertTrue(read.all { it.source == "MANUAL_TEST" })
    }

    @Test
    fun 再次导入是整本替换而不是追加() = runBlocking {
        repo.replace("a", (0 until 5).map { segment(it, it * 1_000L, "旧$it") }, SubtitleSource.ASR)
        repo.replace("a", listOf(segment(0, 0, "新只有一句")), SubtitleSource.ASR)

        val read = db.subtitleSegmentDao().getByAudio("a")
        assertEquals(1, read.size)
        assertEquals("新只有一句", read[0].text)
        assertEquals(0, read[0].segmentIndex)
    }

    @Test
    fun 摘要能分辨数量与来源() = runBlocking {
        assertEquals(0, repo.observeSummary("a").first().count)
        assertEquals(false, repo.observeSummary("a").first().producedByAsr)

        repo.replace("a", listOf(segment(0, 0, "甲"), segment(1, 1_000, "乙")), SubtitleSource.MANUAL_TEST)
        val manual = repo.observeSummary("a").first()
        assertEquals(2, manual.count)
        assertEquals(false, manual.producedByAsr)

        repo.replace("a", listOf(segment(0, 0, "模型给的句子")), SubtitleSource.ASR)
        val asr = repo.observeSummary("a").first()
        assertEquals(1, asr.count)
        assertEquals(true, asr.producedByAsr)
    }

    /** NFR-04 的另一半：字幕跟着书删掉，但统计历史必须活下来 */
    @Test
    fun 删书连带删字幕但不删收听历史() = runBlocking {
        repo.replace("a", listOf(segment(0, 0, "甲")), SubtitleSource.ASR)
        db.listeningSessionDao().insert(
            ListeningSessionEntity(
                audioId = "a",
                audioTitleSnapshot = "第一首",
                durationMsSnapshot = 60_000,
                startedAtMs = 1L,
                endedAtMs = 2L,
                listenedMs = 30_000,
                consumedAudioMs = 30_000,
                sessionStatus = ListeningSessionEntity.Status.FINISHED,
                createdAtMs = 1L,
                updatedAtMs = 2L,
            ),
        )

        db.audioBookDao().delete("a")

        assertEquals(0, db.subtitleSegmentDao().getByAudio("a").size)
        assertEquals(1, db.listeningSessionDao().settledOf("a").size)
    }
}
