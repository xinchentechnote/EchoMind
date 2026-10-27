package com.xinchen.echomind.data.repo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.scan.AudioMetadataReader
import com.xinchen.echomind.data.scan.MediaStoreAudioSource
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本机音频（MediaStore 伪文件夹）导入的仪器化测试：真 MediaStore + 真 Room。
 *
 * 素材不是真音频：MediaStore 行插入 + 写几字节即可，导入/重扫/消失检测只看行元数据，
 * 不解码内容。权限用 UiAutomation 授予/撤销——真实运行时权限状态是这条链路的主角，
 * JVM 里伪造不出（与 [AudioRepositoryImportTest] 抽 TreeGrantStore 的理由相同）。
 */
@RunWith(AndroidJUnit4::class)
class DeviceAudioImportTest {

    private lateinit var db: EchoMindDatabase
    private lateinit var context: Context
    private var clock = 5_000L

    /** 本轮插入的 MediaStore 行，@After 统一清掉，不污染设备媒体库 */
    private val insertedUris = mutableListOf<Uri>()
    private var folderName = ""

    private class FakeGrants : TreeGrantStore {
        override fun persistRead(treeUri: String): String? = null
        override fun hasReadGrant(treeUri: String): Boolean = false
        override fun grantedTreeUris(): List<String> = emptyList()
    }

    /** 本组用例不走 SAF 树，browser 只需存在 */
    private class FakeBrowser : com.xinchen.echomind.domain.importing.TreeBrowser {
        override fun children(dirUri: String) = emptyList<com.xinchen.echomind.domain.importing.TreeEntry>()
        override fun treeDisplayName(treeUri: String): String? = null
    }

    private class FakeReader : AudioMetadataReader {
        override fun durationMs(uri: String): Long? = null
    }

    private class FakeMediaAudio(var granted: Boolean = true) : MediaAudioAccess {
        override fun isGranted(): Boolean = granted
    }

    private fun repository(mediaAudio: MediaAudioAccess) = AudioRepositoryImpl(
        audioBookDao = db.audioBookDao(),
        folderDao = db.importedFolderDao(),
        shelfDao = db.shelfDao(),
        treeBrowser = FakeBrowser(),
        metadataReader = FakeReader(),
        grants = FakeGrants(),
        nowMs = { clock },
        deviceSource = MediaStoreAudioSource(context),
        mediaAudio = mediaAudio,
    )

    /** 插一行 mp3 到 MediaStore（内容是占位字节：本链路不解码） */
    private fun insertAudio(relativeDir: String, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.TITLE, fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(base, values)
            ?: throw AssertionError("MediaStore 插入失败：$relativeDir/$fileName")
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            out.write(ByteArray(64))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
        insertedUris += uri
        return uri
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ui.grantRuntimePermission(context.packageName, permission)

        db = Room.inMemoryDatabaseBuilder(context, EchoMindDatabase::class.java).build()
        folderName = "EchoMindTest_${System.nanoTime()}"
    }

    @After
    fun tearDown() {
        for (uri in insertedUris) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        db.close()
    }

    @Test
    fun 整夹导入建伪文件夹与书籍() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        insertAudio("Music/$folderName/sub", "02.mp3")

        val outcome = repository(FakeMediaAudio()).importDeviceFolder("external_primary", "Music/$folderName")

        assertEquals(2, outcome.added)
        val expectedTreeUri = "mediastore://external_primary/Music/$folderName"
        assertEquals(stableIdFromUri(expectedTreeUri), outcome.folderId)
        val folder = db.importedFolderDao().byTreeUri(expectedTreeUri)!!
        assertEquals("内部存储/Music/$folderName", folder.displayName)
        assertEquals(ImportedFolderEntity.PermissionState.Granted, folder.permissionState)

        assertEquals(2, db.audioBookDao().count())
        val nested = db.audioBookDao().getById(stableIdFromUri(insertedUris[1].toString()))!!
        assertEquals("Music/$folderName/sub/02.mp3", nested.relativePath)
        assertEquals(folder.id, nested.folderId)
        // MediaStore 没给时长（0）→ 记「时长未知」，不编造
        assertTrue(nested.durationReadFailed)
    }

    @Test
    fun 重复导入不重复建记录() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        val repo = repository(FakeMediaAudio())
        repo.importDeviceFolder("external_primary", "Music/$folderName")
        val second = repo.importDeviceFolder("external_primary", "Music/$folderName")

        assertEquals(0, second.added)
        assertEquals(1, second.unchanged)
        assertEquals(1, db.audioBookDao().count())
    }

    /** 单文件导入的差量只看这一个文件：同伪文件夹下没扫的其余书不许被误标 MISSING */
    @Test
    fun 单文件导入不误伤同夹其他书() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        insertAudio("Music/$folderName/sub", "02.mp3")
        val repo = repository(FakeMediaAudio())
        repo.importDeviceFolder("external_primary", "Music/$folderName")

        val scan = MediaStoreAudioSource(context).filesUnder("external_primary", "Music/$folderName")
        val first = scan.first { it.fileName == "01.mp3" }
        val outcome = repo.importDeviceFile(first)

        assertEquals(0, outcome.added)
        assertEquals(1, outcome.unchanged)
        assertEquals(0, outcome.vanished)
        val nested = db.audioBookDao().getById(stableIdFromUri(insertedUris[1].toString()))!!
        assertEquals("PRESENT", nested.fileStatus)
    }

    @Test
    fun 文件从MediaStore删除后重扫标MISSING() = runBlocking {
        val goneUri = insertAudio("Music/$folderName", "01.mp3")
        val keptUri = insertAudio("Music/$folderName", "02.mp3")
        val repo = repository(FakeMediaAudio())
        repo.importDeviceFolder("external_primary", "Music/$folderName")

        context.contentResolver.delete(goneUri, null, null)
        insertedUris.remove(goneUri)
        val outcome = repo.importDeviceFolder("external_primary", "Music/$folderName")

        assertEquals(1, outcome.vanished)
        val gone = db.audioBookDao().getById(stableIdFromUri(goneUri.toString()))!!
        assertEquals("MISSING", gone.fileStatus)
        val kept = db.audioBookDao().getById(stableIdFromUri(keptUri.toString()))!!
        assertEquals("PRESENT", kept.fileStatus)
    }

    @Test
    fun rescanAll把伪文件夹交给MediaStore重扫() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        val repo = repository(FakeMediaAudio())
        repo.importDeviceFolder("external_primary", "Music/$folderName")

        val outcomes = repo.rescanAll()
        val device = outcomes.single { it.folderId == stableIdFromUri("mediastore://external_primary/Music/$folderName") }
        assertEquals(0, device.added)
        assertEquals(1, device.unchanged)
        assertEquals(1, db.audioBookDao().count())
    }

    /** 目录管理（2026-10-28）：书架上按夹重扫走同一段 MediaStore 分支 */
    @Test
    fun rescanFolder对伪文件夹走同一段分支() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        val repo = repository(FakeMediaAudio())
        val folderId = stableIdFromUri("mediastore://external_primary/Music/$folderName")
        repo.importDeviceFolder("external_primary", "Music/$folderName")

        val outcome = repo.rescanFolder(folderId)

        assertEquals(folderId, outcome.folderId)
        assertEquals(0, outcome.added)
        assertEquals(1, outcome.unchanged)
        assertEquals(1, db.audioBookDao().count())
    }

    @Test
    fun 媒体权限被撤后导入抛出且重扫标REVOKED() = runBlocking {
        insertAudio("Music/$folderName", "01.mp3")
        repository(FakeMediaAudio(granted = true)).importDeviceFolder("external_primary", "Music/$folderName")

        val revokedRepo = repository(FakeMediaAudio(granted = false))
        val error = runCatching {
            revokedRepo.importDeviceFolder("external_primary", "Music/$folderName")
        }.exceptionOrNull()
        assertTrue(error is PermissionRevokedException)

        val outcomes = revokedRepo.rescanAll()
        val device = outcomes.single { it.folderId == stableIdFromUri("mediastore://external_primary/Music/$folderName") }
        assertTrue(device.permissionRevoked)
        assertEquals(
            ImportedFolderEntity.PermissionState.Revoked,
            db.importedFolderDao().getAll().first { it.id == device.folderId }.permissionState,
        )
        // 撤权只是不能读，已入库的书与历史不删（与 SAF 失效文件夹同一语义）
        assertEquals(1, db.audioBookDao().count())
    }

    @Test
    fun MediaStore扫描能按子树取文件且不混入别的目录(): Unit = runBlocking {
        val own = insertAudio("Music/$folderName", "01.mp3")
        val neighbor = insertAudio("Music/${folderName}1", "02.mp3")
        try {
            val files = MediaStoreAudioSource(context).filesUnder("external_primary", "Music/$folderName")
            assertEquals(listOf(own.toString()), files.map { it.uri })
            assertNotNull(db)
        } finally {
            context.contentResolver.delete(neighbor, null, null)
        }
    }
}
