package com.xinchen.echomind.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 层的仪器化测试（docs/testing.md §3：DAO/外键/JOIN 必须在真 SQLite 上验，不能用 JVM 单测代替）。
 *
 * 重点守住两条：
 * - FR-106 重复 URI 不产生第二条记录；
 * - 重扫/重复授权不能把 playback_state 里的播放记忆连带删掉（@Upsert 而非 @Insert(REPLACE)，
 *   因为 REPLACE 在 SQLite 是 DELETE+INSERT，会触发 ON DELETE CASCADE）。
 */
@RunWith(AndroidJUnit4::class)
class AudioBookDaoTest {

    private lateinit var db: EchoMindDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EchoMindDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun folder(id: String, uri: String = "tree://$id") = ImportedFolderEntity(
        id = id,
        treeUri = uri,
        displayName = "文件夹$id",
        grantedAtMs = 1_000L,
        lastScanAtMs = 0L,
        permissionState = ImportedFolderEntity.PermissionState.Granted,
        isHidden = false,
    )

    private fun book(
        id: String,
        folderId: String = "f1",
        title: String = "书$id",
        uri: String = "file://$folderId/$id.mp3",
        sizeBytes: Long = 1000L,
        durationMs: Long = 0L,
        fileStatus: String = AudioBookEntity.FileStatus.Present,
    ) = AudioBookEntity(
        id = id,
        uri = uri,
        folderId = folderId,
        relativePath = "$id.mp3",
        fileName = "$id.mp3",
        title = title,
        sizeBytes = sizeBytes,
        fileLastModifiedMs = 2_000L,
        durationMs = durationMs,
        addedAtMs = 3_000L,
        lastScannedAtMs = 3_000L,
        updatedAtMs = 3_000L,
        fileStatus = fileStatus,
    )

    private fun state(
        audioId: String,
        lastPositionMs: Long = 0L,
        lastPlayedAtMs: Long = 0L,
        speed: Float = 1.0f,
    ) = PlaybackStateEntity(
        audioId = audioId,
        lastPositionMs = lastPositionMs,
        lastPlayedAtMs = lastPlayedAtMs,
        playbackSpeed = speed,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Test
    fun `同一URI重复写入只保留一条记录`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsert(book("b1", title = "旧标题", sizeBytes = 1000))
        db.audioBookDao().upsert(book("b1", title = "新标题", sizeBytes = 2048))

        assertEquals(1, db.audioBookDao().count())
        val stored = db.audioBookDao().getById("b1")!!
        assertEquals("新标题", stored.title)
        assertEquals(2048L, stored.sizeBytes)
    }

    /** 这是 Phase 3 修掉的真实缺陷：REPLACE 会级联清空播放记忆 */
    @Test
    fun `重扫更新不会丢掉播放位置与统计缓存`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsert(book("b1"))
        db.playbackStateDao().ensureRow(state("b1", lastPositionMs = 123_456L, lastPlayedAtMs = 9_000L))

        db.audioBookDao().upsert(book("b1", title = "改了名字", sizeBytes = 4096))

        val kept = db.playbackStateDao().get("b1")
        assertEquals(123_456L, kept?.lastPositionMs)
        assertEquals(9_000L, kept?.lastPlayedAtMs)
    }

    @Test
    fun `文件夹重复授权不会清掉它下面的书`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(listOf(book("b1"), book("b2")))

        db.importedFolderDao().upsert(
            folder("f1").copy(
                displayName = "改过的显示名",
                permissionState = ImportedFolderEntity.PermissionState.Granted,
            ),
        )

        assertEquals(2, db.audioBookDao().countOfFolder("f1"))
        assertEquals("改过的显示名", db.importedFolderDao().byTreeUri("tree://f1")?.displayName)
    }

    @Test
    fun `移除文件夹级联删除书但表结构允许历史独立存在`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(listOf(book("b1"), book("b2")))
        db.playbackStateDao().ensureRow(state("b1", lastPositionMs = 500L))

        db.importedFolderDao().delete("f1")

        assertEquals(0, db.audioBookDao().count())
        assertEquals(0, db.audioBookDao().countOfFolder("f1"))
    }

    @Test
    fun `presentIds用来跳过已在书架的文件`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsert(book("b1"))

        assertEquals(listOf("b1"), db.audioBookDao().presentIds(listOf("b1", "nope")))
        assertEquals(emptyList<String>(), db.audioBookDao().presentIds(listOf("nope")))
    }

    @Test
    fun `书架投影在没有播放记录时给出可用默认值`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsert(book("b1", title = "Alpha", durationMs = 0L))

        val row = db.shelfDao().observeAll("").first().single()
        assertEquals("Alpha", row.title)
        assertEquals(0L, row.lastPositionMs)
        assertEquals(0L, row.lastPlayedAtMs)
        assertEquals(1.0f, row.playbackSpeed)
    }

    @Test
    fun `最近播放只含播过的且按时间倒序`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(listOf(book("b1"), book("b2"), book("b3")))
        db.playbackStateDao().ensureRow(state("b1", lastPlayedAtMs = 0L))
        db.playbackStateDao().ensureRow(state("b2", lastPlayedAtMs = 300L))
        db.playbackStateDao().ensureRow(state("b3", lastPlayedAtMs = 200L))

        val recent = db.shelfDao().observeRecent("").first().map { it.id }
        assertEquals(listOf("b2", "b3"), recent)
    }

    @Test
    fun `继续收听跳过文件已消失的书`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(
            listOf(
                book("b1", title = "还在", fileStatus = AudioBookEntity.FileStatus.Present),
                book("b2", title = "丢了", fileStatus = AudioBookEntity.FileStatus.Missing),
            ),
        )
        db.playbackStateDao().ensureRow(state("b1", lastPlayedAtMs = 100L))
        db.playbackStateDao().ensureRow(state("b2", lastPlayedAtMs = 900L))

        val last = db.shelfDao().observeLastPlayed().first()
        assertEquals("b1", last?.id)
    }

    @Test
    fun `没有播放记录时继续收听为空`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsert(book("b1"))
        assertNull(db.shelfDao().observeLastPlayed().first())
    }

    @Test
    fun `标题搜索对ASCII大小写不敏感`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(listOf(book("b1", title = "Alpha"), book("b2", title = "beta"), book("b3", title = "第三本")))

        assertEquals(listOf("b1"), db.shelfDao().observeAll("lph").first().map { it.id })
        assertEquals(listOf("b2"), db.shelfDao().observeAll("BET").first().map { it.id })
        assertEquals(listOf("b3"), db.shelfDao().observeAll("第三").first().map { it.id })
        // 排序：COLLATE NOCASE 让英文在前、按字母序，中文按 Unicode 序排在后面
        assertEquals(listOf("b1", "b2", "b3"), db.shelfDao().observeAll("").first().map { it.id })
    }

    @Test
    fun `收藏筛选与改名持久化`() = runBlocking {
        db.importedFolderDao().upsert(folder("f1"))
        db.audioBookDao().upsertAll(listOf(book("b1"), book("b2")))

        db.audioBookDao().setFavorite("b1", true, 5_000L)
        db.audioBookDao().rename("b2", "手工标题", 5_000L)

        assertEquals(listOf("b1"), db.shelfDao().observeFavorites("").first().map { it.id })
        val renamed = db.audioBookDao().getById("b2")!!
        assertEquals("手工标题", renamed.title)
        assertEquals(true, renamed.titleIsManual)
    }
}
