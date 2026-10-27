package com.xinchen.echomind.ui.device

import android.net.Uri
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ImportOutcome
import com.xinchen.echomind.data.repo.MediaAudioAccess
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.DeviceAudioFolderRef
import com.xinchen.echomind.domain.model.TranscriptionTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 本机音频页的状态机：选卷 → 下钻/上退、导入后角标刷新、权限门（JVM，假时钟=StandardTestDispatcher） */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAudioViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeMediaAudio(var granted: Boolean) : MediaAudioAccess {
        override fun isGranted(): Boolean = granted
    }

    private class FakeRepo(
        var entries: List<DeviceAudioEntry> = emptyList(),
        var uris: Set<String> = emptySet(),
        var outcome: ImportOutcome? = null,
    ) : AudioRepository {
        val folderImports = mutableListOf<Pair<String, String>>()
        val fileImports = mutableListOf<DeviceAudioEntry>()
        var access: MediaAudioAccess? = null

        override fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>> = MutableStateFlow(emptyList())
        override fun lastPlayed(): Flow<ShelfRow?> = MutableStateFlow(null)
        override fun folders(): Flow<List<ImportedFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun transcriptionTarget(audioId: String): TranscriptionTarget? = unsupported()
        override suspend fun importFolder(treeUri: Uri): ImportOutcome = unsupported()
        override suspend fun rescanAll(): List<ImportOutcome> = unsupported()
        override suspend fun rescanFolder(folderId: String): ImportOutcome = unsupported()
        override suspend fun rename(audioId: String, title: String) = unsupported()
        override suspend fun setFavorite(audioId: String, favorite: Boolean) = unsupported()
        override suspend fun removeBook(audioId: String) = unsupported()
        override suspend fun removeFolder(folderId: String) = unsupported()
        override suspend fun markPermissionRevoked(folderId: String) = unsupported()

        override suspend fun importDeviceFolder(volume: String, relativePath: String): ImportOutcome {
            folderImports += volume to relativePath
            return outcome ?: zeroOutcome()
        }

        override suspend fun importDeviceFile(file: DeviceAudioEntry): ImportOutcome {
            fileImports += file
            return outcome ?: zeroOutcome()
        }

        override suspend fun scanDeviceAudio(): List<DeviceAudioEntry> = entries
        override suspend fun deviceImportedUris(): Set<String> = uris
        override val mediaAudioAccess: MediaAudioAccess? get() = access

        private fun zeroOutcome() = ImportOutcome("f", 0, 0, 0, 0, 0, 0, truncated = false)

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private fun entry(volume: String = "external_primary", path: String, name: String) =
        DeviceAudioEntry(volume, path, name, "content://media/$volume/$path/$name", 10L, 1L, 60_000L)

    @Test
    fun 扫描后根层按卷分组() = runTest {
        val repo = FakeRepo(
            entries = listOf(
                entry(path = "Music", name = "a.mp3"),
                entry(volume = "sdcard1", path = "Music", name = "b.mp3"),
            ),
        )
        val vm = DeviceAudioViewModel(repo)
        vm.scan()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.totalFound)
        assertEquals(
            listOf("external_primary", "sdcard1"),
            vm.state.value.listing.folders.map { it.ref.volume },
        )
        assertEquals(2, vm.state.value.listing.folders.sumOf { it.fileCount })
    }

    @Test
    fun 下钻与上退在状态里走完整层级() = runTest {
        val repo = FakeRepo(
            entries = listOf(
                entry(path = "Music", name = "a.mp3"),
                entry(path = "Music/Podcasts", name = "b.mp3"),
            ),
        )
        val vm = DeviceAudioViewModel(repo)
        vm.scan()
        advanceUntilIdle()

        vm.openFolder(DeviceAudioFolderRef("external_primary", ""))
        advanceUntilIdle()
        assertEquals("external_primary", vm.state.value.volume)
        assertNull(vm.state.value.path)
        assertEquals(listOf("Music"), vm.state.value.listing.folders.map { it.ref.displayName })
        assertTrue(vm.state.value.canGoUp)

        vm.openFolder(DeviceAudioFolderRef("external_primary", "Music"))
        advanceUntilIdle()
        assertEquals("Music", vm.state.value.path)
        assertEquals(listOf("Podcasts"), vm.state.value.listing.folders.map { it.ref.displayName })
        assertEquals(listOf("a.mp3"), vm.state.value.listing.files.map { it.fileName })

        vm.goUp()
        advanceUntilIdle()
        assertNull(vm.state.value.path)
        vm.goUp()
        advanceUntilIdle()
        assertNull(vm.state.value.volume)
        assertEquals(false, vm.state.value.canGoUp)
    }

    @Test
    fun 整夹导入后角标集合刷新并给出一句话() = runTest {
        val file = entry(path = "Music", name = "a.mp3")
        val repo = FakeRepo(
            entries = listOf(file),
            outcome = ImportOutcome("f", added = 2, changed = 0, vanished = 0, revived = 0, unchanged = 0, alreadyOnShelf = 1, truncated = false),
        )
        val vm = DeviceAudioViewModel(repo)
        vm.scan()
        advanceUntilIdle()

        vm.importFolder(DeviceAudioFolderRef("external_primary", "Music"))
        advanceUntilIdle()

        assertEquals(listOf("external_primary" to "Music"), repo.folderImports)
        assertEquals("已导入 2 首，1 首已在书架", vm.state.value.message)
    }

    @Test
    fun 单文件导入新文件给提示_已导入集合来自仓储() = runTest {
        val file = entry(path = "Music", name = "a.mp3")
        val repo = FakeRepo(
            entries = listOf(file),
            outcome = ImportOutcome("f", added = 1, changed = 0, vanished = 0, revived = 0, unchanged = 0, alreadyOnShelf = 0, truncated = false),
        )
        val vm = DeviceAudioViewModel(repo)
        vm.scan()
        advanceUntilIdle()

        vm.importFile(file)
        advanceUntilIdle()

        assertEquals(listOf(file), repo.fileImports)
        assertEquals("已加入书架：a", vm.state.value.message)
    }

    @Test
    fun 已在书架的文件静默不刷提示() = runTest {
        val file = entry(path = "Music", name = "a.mp3")
        val repo = FakeRepo(
            entries = listOf(file),
            uris = setOf(file.uri),
            outcome = ImportOutcome("f", added = 0, changed = 0, vanished = 0, revived = 0, unchanged = 1, alreadyOnShelf = 1, truncated = false),
        )
        val vm = DeviceAudioViewModel(repo)
        vm.scan()
        advanceUntilIdle()

        vm.importFile(file)
        advanceUntilIdle()

        assertNull(vm.state.value.message)
    }

    @Test
    fun 权限授予则开扫_拒绝则进Blocked() = runTest {
        val access = FakeMediaAudio(granted = false)
        val repo = FakeRepo(entries = listOf(entry(path = "Music", name = "a.mp3")))
        repo.access = access
        val vm = DeviceAudioViewModel(repo)
        advanceUntilIdle()
        assertEquals(AudioPermissionState.Unknown, vm.state.value.permission)

        access.granted = true
        vm.refreshPermission()
        advanceUntilIdle()
        assertEquals(AudioPermissionState.Granted, vm.state.value.permission)
        assertEquals(1, vm.state.value.totalFound)

        access.granted = false
        vm.onPermissionResult(granted = false)
        assertEquals(AudioPermissionState.Blocked, vm.state.value.permission)
    }
}
