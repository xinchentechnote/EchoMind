package com.xinchen.echomind.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.scan.AudioMetadataReader
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import com.xinchen.echomind.domain.importing.TreeBrowser
import com.xinchen.echomind.domain.importing.TreeEntry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导入编排的仪器化测试：真 Room（唯一索引、外键、级联都要真 SQLite）+ 假目录树 + 假授权。
 *
 * [TreeGrantStore] 这个接缝存在的理由：ContentResolver 的持久化授权无法在用例里授予或撤销，
 * 不抽出来就没法验证「授权失效」这两条路径（FR-110）。
 */
@RunWith(AndroidJUnit4::class)
class AudioRepositoryImportTest {

    private lateinit var db: EchoMindDatabase
    private var clock = 5_000L

    private val treeRoot = "tree://primary%3AMusic"
    private val otherRoot = "tree://primary%3AOther"

    private class FakeBrowser(var tree: Map<String, List<TreeEntry>>) : TreeBrowser {
        override fun children(dirUri: String): List<TreeEntry> = tree[dirUri] ?: emptyList()
        override fun treeDisplayName(treeUri: String): String? = "显示名"
    }

    private class FakeGrants(
        var granted: MutableSet<String> = mutableSetOf(),
        var rejectReason: String? = null,
    ) : TreeGrantStore {
        override fun persistRead(treeUri: String): String? {
            rejectReason?.let { return it }
            granted.add(treeUri)
            return null
        }

        override fun hasReadGrant(treeUri: String): Boolean = treeUri in granted
        override fun grantedTreeUris(): List<String> = granted.toList()
    }

    private class FakeReader(var durations: Map<String, Long?> = emptyMap()) : AudioMetadataReader {
        val requested = mutableListOf<String>()
        override fun durationMs(uri: String): Long? {
            requested += uri
            return durations[uri]
        }
    }

    private fun repository(
        browser: TreeBrowser,
        grants: TreeGrantStore,
        reader: AudioMetadataReader,
        wmaStore: WmaPlaybackStore? = null,
        wmaPrepare: WmaImportPreparer? = null,
    ) =
        AudioRepositoryImpl(
            audioBookDao = db.audioBookDao(),
            folderDao = db.importedFolderDao(),
            shelfDao = db.shelfDao(),
            treeBrowser = browser,
            metadataReader = reader,
            grants = grants,
            nowMs = { clock },
            wmaStore = wmaStore,
            wmaPrepare = wmaPrepare,
        )

    private fun file(name: String, uri: String, size: Long = 1000L) =
        TreeEntry(name, uri, isDirectory = false, sizeBytes = size, lastModifiedMs = 2000L)

    private fun dir(name: String, uri: String) =
        TreeEntry(name, uri, isDirectory = true, sizeBytes = 0, lastModifiedMs = 0)

    /** 根目录：一首平铺 + 一首在子目录 + 一个非音频文件 */
    private fun sampleTree(): Map<String, List<TreeEntry>> = mapOf(
        treeRoot to listOf(
            file("01 - 三体.mp3", "$treeRoot/01 - 三体.mp3"),
            dir("second", "$treeRoot/second"),
            file("notes.txt", "$treeRoot/notes.txt"),
        ),
        "$treeRoot/second" to listOf(file("02 - 第二章.mp3", "$treeRoot/second/02 - 第二章.mp3")),
    )

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EchoMindDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun 导入写入文件夹与书籍() = runBlocking {
        val grants = FakeGrants()
        val reader = FakeReader(
            mapOf(
                "$treeRoot/01 - 三体.mp3" to 30_000L,
                "$treeRoot/second/02 - 第二章.mp3" to 45_000L,
            ),
        )
        val outcome = repository(FakeBrowser(sampleTree()), grants, reader).importFolder(Uri.parse(treeRoot))

        assertEquals(2, outcome.added)
        assertEquals(0, outcome.changed)
        assertEquals(0, outcome.vanished)
        assertEquals(stableIdFromUri(treeRoot), outcome.folderId)

        val folder = db.importedFolderDao().byTreeUri(treeRoot)!!
        assertEquals("显示名", folder.displayName)
        assertEquals(ImportedFolderEntity.PermissionState.Granted, folder.permissionState)
        assertEquals(clock, folder.lastScanAtMs)

        assertEquals(2, db.audioBookDao().count())
        val first = db.audioBookDao().getById(stableIdFromUri("$treeRoot/01 - 三体.mp3"))!!
        assertEquals("三体", first.title)
        assertEquals(30_000L, first.durationMs)
        assertFalse(first.durationReadFailed)
        assertEquals(AudioBookEntity.FileStatus.Present, first.fileStatus)
        val nested = db.audioBookDao().getById(stableIdFromUri("$treeRoot/second/02 - 第二章.mp3"))!!
        assertEquals("second/02 - 第二章.mp3", nested.relativePath)
        assertEquals("第二章", nested.title)
        assertEquals(folder.id, nested.folderId)
    }

    @Test
    fun 重复导入不产生第二条记录() = runBlocking {
        val repo = repository(FakeBrowser(sampleTree()), FakeGrants(), FakeReader())
        repo.importFolder(Uri.parse(treeRoot))
        val second = repo.importFolder(Uri.parse(treeRoot))

        assertEquals(0, second.added)
        assertEquals(2, second.unchanged)
        assertEquals(0, second.alreadyOnShelf)
        assertEquals(2, db.audioBookDao().count())
    }

    @Test
    fun 同一文件被两个文件夹覆盖时留在原来的文件夹() = runBlocking {
        val browser = FakeBrowser(sampleTree())
        val grants = FakeGrants()
        val repo = repository(browser, grants, FakeReader())
        repo.importFolder(Uri.parse(treeRoot))

        // 第二个树根列出同样的 URI：书必须留在首次导入它的文件夹里（FR-106）
        val shared = listOf(
            file("01 - 三体.mp3", "$treeRoot/01 - 三体.mp3"),
            file("02 - 第二章.mp3", "$treeRoot/second/02 - 第二章.mp3"),
        )
        browser.tree = browser.tree + mapOf(otherRoot to shared)
        val outcome = repo.importFolder(Uri.parse(otherRoot))

        assertEquals(0, outcome.added)
        assertEquals(2, outcome.alreadyOnShelf)
        assertEquals(2, db.audioBookDao().count())
        assertEquals(2, db.audioBookDao().countOfFolder(stableIdFromUri(treeRoot)))
        assertEquals(0, db.audioBookDao().countOfFolder(stableIdFromUri(otherRoot)))
    }

    @Test
    fun 授权无法持久化时导入失败且不写库() = runBlocking {
        val grants = FakeGrants(rejectReason = "IllegalArgumentException(模拟撤销)")
        val repo = repository(FakeBrowser(sampleTree()), grants, FakeReader())

        val error = runCatching { repo.importFolder(Uri.parse(treeRoot)) }.exceptionOrNull()
        assertTrue(error is PermissionRevokedException)
        assertTrue(error!!.message!!.contains("IllegalArgumentException"))
        assertEquals(0, db.audioBookDao().count())
        assertNull(db.importedFolderDao().byTreeUri(treeRoot))
    }

    @Test
    fun 重扫跳过授权失效的文件夹但继续其他文件夹() = runBlocking {
        val browser = FakeBrowser(sampleTree())
        val grants = FakeGrants()
        val repo = repository(browser, grants, FakeReader())
        repo.importFolder(Uri.parse(treeRoot))
        browser.tree = browser.tree + mapOf(otherRoot to listOf(file("b.mp3", "$otherRoot/b.mp3")))
        repo.importFolder(Uri.parse(otherRoot))

        grants.granted.remove(otherRoot)
        val outcomes = repo.rescanAll()

        assertEquals(2, outcomes.size)
        val revoked = outcomes.single { it.permissionRevoked }
        assertEquals(stableIdFromUri(otherRoot), revoked.folderId)
        assertEquals(
            ImportedFolderEntity.PermissionState.Revoked,
            db.importedFolderDao().getAll().first { it.id == revoked.folderId }.permissionState,
        )
        val scanned = outcomes.single { !it.permissionRevoked }
        assertEquals(stableIdFromUri(treeRoot), scanned.folderId)
        assertEquals(0, scanned.added)
        assertEquals(2, scanned.unchanged)
        // 授权失效只是跳过，不删任何记录：两个文件夹的三首都在
        assertEquals(3, db.audioBookDao().count())
    }

    @Test
    fun 文件消失只标状态不删记录() = runBlocking {
        val browser = FakeBrowser(sampleTree())
        val repo = repository(browser, FakeGrants(), FakeReader())
        repo.importFolder(Uri.parse(treeRoot))

        browser.tree = mapOf(treeRoot to listOf(file("01 - 三体.mp3", "$treeRoot/01 - 三体.mp3")))
        val outcome = repo.importFolder(Uri.parse(treeRoot))

        assertEquals(1, outcome.vanished)
        assertEquals(2, db.audioBookDao().count())
        val gone = db.audioBookDao().getById(stableIdFromUri("$treeRoot/second/02 - 第二章.mp3"))!!
        assertEquals(AudioBookEntity.FileStatus.Missing, gone.fileStatus)
    }

    /** 设备实测的 bug：文件移走又原样移回来（size/mtime 都没变），书架必须重新可播而不是永远灰着 */
    @Test
    fun 文件原样回来时状态从MISSING翻回PRESENT() = runBlocking {
        val browser = FakeBrowser(sampleTree())
        val repo = repository(browser, FakeGrants(), FakeReader())
        repo.importFolder(Uri.parse(treeRoot))

        val nestedId = stableIdFromUri("$treeRoot/second/02 - 第二章.mp3")
        browser.tree = mapOf(treeRoot to listOf(file("01 - 三体.mp3", "$treeRoot/01 - 三体.mp3")))
        repo.importFolder(Uri.parse(treeRoot))
        assertEquals(AudioBookEntity.FileStatus.Missing, db.audioBookDao().getById(nestedId)!!.fileStatus)

        browser.tree = sampleTree()
        clock = 9_000L
        val outcome = repo.importFolder(Uri.parse(treeRoot))

        assertEquals(1, outcome.revived)
        assertEquals(0, outcome.added)
        assertEquals(0, outcome.changed)
        assertEquals(0, outcome.vanished)
        assertEquals(1, outcome.unchanged)
        val back = db.audioBookDao().getById(nestedId)!!
        assertEquals(AudioBookEntity.FileStatus.Present, back.fileStatus)
        assertEquals(9_000L, back.updatedAtMs)
        assertEquals(2, db.audioBookDao().count())
    }

    @Test
    fun 内容变化保留手动标题并把字幕标为过期() = runBlocking {
        val browser = FakeBrowser(sampleTree())
        val repo = repository(browser, FakeGrants(), FakeReader())
        repo.importFolder(Uri.parse(treeRoot))

        val id = stableIdFromUri("$treeRoot/01 - 三体.mp3")
        db.audioBookDao().rename(id, "我起的名字", clock)
        db.audioBookDao().setTranscriptionState(listOf(id), AudioBookEntity.TranscriptionState.Ready, clock)

        browser.tree = browser.tree + mapOf(
            treeRoot to listOf(file("01 - 三体.mp3", "$treeRoot/01 - 三体.mp3", size = 9999L), dir("second", "$treeRoot/second")),
        )
        clock = 7_000L
        val outcome = repo.importFolder(Uri.parse(treeRoot))

        assertEquals(1, outcome.changed)
        val book = db.audioBookDao().getById(id)!!
        assertEquals("我起的名字", book.title)
        assertTrue(book.titleIsManual)
        assertEquals(AudioBookEntity.TranscriptionState.Expired, book.transcriptionState)
        assertEquals(9999L, book.sizeBytes)
        assertEquals(7_000L, book.updatedAtMs)
    }

    @Test
    fun 时长读取失败记为未知() = runBlocking {
        val reader = FakeReader(mapOf("$treeRoot/01 - 三体.mp3" to 30_000L))
        repository(FakeBrowser(sampleTree()), FakeGrants(), reader).importFolder(Uri.parse(treeRoot))

        val book = db.audioBookDao().getById(stableIdFromUri("$treeRoot/second/02 - 第二章.mp3"))!!
        assertEquals(0L, book.durationMs)
        assertTrue(book.durationReadFailed)
    }

    @Test
    fun WMA先入库并交给后台准备而不阻塞整批导入() = runBlocking {
        val wmaUri = "$treeRoot/book.wma"
        val browser = FakeBrowser(mapOf(treeRoot to listOf(file("book.wma", wmaUri))))
        val reader = FakeReader()
        val scheduled = mutableListOf<Triple<String, String, Boolean>>()
        val preparer = object : WmaImportPreparer {
            override fun schedule(audioId: String, uri: String, force: Boolean): Boolean {
                scheduled += Triple(audioId, uri, force)
                return true
            }

            override fun cancel(audioId: String) = Unit
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WmaPlaybackStore(File(context.cacheDir, "wma-import-test-${System.nanoTime()}"))

        val outcome = repository(browser, FakeGrants(), reader, store, preparer).importFolder(Uri.parse(treeRoot))

        val audioId = stableIdFromUri(wmaUri)
        assertEquals(1, outcome.added)
        assertEquals(1, outcome.preparingWma)
        assertTrue("WMA 不应走可能卡住的系统元数据读取器：${reader.requested}", reader.requested.isEmpty())
        assertEquals(
            "新入库必须强制覆盖 v0.1.2 中断后可能遗留的半截转码文件",
            listOf(Triple(audioId, wmaUri, true)),
            scheduled,
        )
        val book = db.audioBookDao().getById(audioId)!!
        assertEquals(0L, book.durationMs)
        assertTrue(book.durationReadFailed)
    }
}
