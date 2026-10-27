package com.xinchen.echomind.ui.shelf

import android.net.Uri
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ImportOutcome
import com.xinchen.echomind.data.repo.MediaAudioAccess
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
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
import org.junit.Before
import org.junit.Test

/**
 * 回归：曾经 tab/query 存在两份状态里，点「收藏」后列表换了但 `state.tab` 仍是 All，
 * 于是 TabRow 高亮不动、空态文案也读错。这里钉住「状态与查询条件同源」。
 *
 * 目录管理（2026-10-28）：文件夹筛选与 tab/query 正交，计数角标来自全量行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(
        var rows: List<ShelfRow> = emptyList(),
        var folders: List<ImportedFolderEntity> = emptyList(),
    ) : AudioRepository {
        val requests = mutableListOf<Pair<ShelfTab, String>>()
        val rescannedFolders = mutableListOf<String>()
        val removedFolders = mutableListOf<String>()
        var nextRescanOutcome: ImportOutcome? = null
        val shelfFlow = MutableStateFlow<List<ShelfRow>>(emptyList())
        private val folderFlow = MutableStateFlow<List<ImportedFolderEntity>>(emptyList())

        override fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>> {
            requests += tab to query
            return shelfFlow
        }

        override fun lastPlayed(): Flow<ShelfRow?> = MutableStateFlow(null)
        override fun folders(): Flow<List<ImportedFolderEntity>> = folderFlow

        fun emit() {
            shelfFlow.value = rows
            folderFlow.value = folders
        }

        override suspend fun transcriptionTarget(audioId: String): TranscriptionTarget? = unsupported()
        override suspend fun importFolder(treeUri: Uri): ImportOutcome = unsupported()
        override suspend fun importDeviceFolder(volume: String, relativePath: String): ImportOutcome = unsupported()
        override suspend fun importDeviceFile(file: DeviceAudioEntry): ImportOutcome = unsupported()
        override suspend fun scanDeviceAudio(): List<DeviceAudioEntry> = unsupported()
        override suspend fun deviceImportedUris(): Set<String> = unsupported()
        override val mediaAudioAccess: MediaAudioAccess? = null
        override suspend fun rescanAll(): List<ImportOutcome> = unsupported()

        override suspend fun rescanFolder(folderId: String): ImportOutcome {
            rescannedFolders += folderId
            return nextRescanOutcome ?: ImportOutcome(folderId, 0, 0, 0, 0, 0, 0, truncated = false)
        }

        override suspend fun rename(audioId: String, title: String) = unsupported()
        override suspend fun setFavorite(audioId: String, favorite: Boolean) = unsupported()
        override suspend fun removeBook(audioId: String) = unsupported()

        override suspend fun removeFolder(folderId: String) {
            removedFolders += folderId
        }

        override suspend fun markPermissionRevoked(folderId: String) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private fun row(id: String, folderId: String, title: String = "书$id") = ShelfRow(
        id = id, uri = "content://x/$id", title = title, fileName = "$id.mp3",
        durationMs = 60_000, sizeBytes = 1, fileStatus = "PRESENT", transcriptionState = "NONE",
        isFavorite = false, lastPositionMs = 0, lastPlayedAtMs = 0, playbackSpeed = 1f, folderId = folderId,
    )

    private fun folder(id: String, name: String) = ImportedFolderEntity(
        id = id, treeUri = "tree://$id", displayName = name, grantedAtMs = 1,
    )

    @Test
    fun `切换 tab 后状态与查询条件一起变`() = runTest {
        val repo = FakeRepo()
        val vm = ShelfViewModel(repo)
        advanceUntilIdle()

        vm.onTabSelected(ShelfTab.Favorites)
        advanceUntilIdle()

        assertEquals(ShelfTab.Favorites, vm.state.value.tab)
        assertEquals(ShelfTab.Favorites, repo.requests.last().first)
    }

    @Test
    fun `搜索词同时进入状态与查询条件`() = runTest {
        val repo = FakeRepo()
        val vm = ShelfViewModel(repo)
        advanceUntilIdle()

        vm.onQueryChanged("三体")
        advanceUntilIdle()

        assertEquals("三体", vm.state.value.query)
        assertEquals("三体", repo.requests.last().second)
    }

    @Test
    fun `选中文件夹后只看该文件夹的书且计数来自全量`() = runTest {
        val repo = FakeRepo(
            rows = listOf(row("a", "f1"), row("b", "f1"), row("c", "f2")),
            folders = listOf(folder("f1", "第一卷"), folder("f2", "第二卷")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()

        vm.onFolderSelected("f1")
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.state.value.rows.map { it.id })
        // 计数角标来自全量行：选中 f1 后 f2 的计数不能归零
        assertEquals(mapOf("f1" to 2, "f2" to 1), vm.state.value.folderCounts)

        vm.onFolderSelected(null)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.rows.size)
    }

    @Test
    fun `按文件夹重扫给出单夹结果`() = runTest {
        val repo = FakeRepo(
            folders = listOf(folder("f1", "第一卷")),
            )
        repo.nextRescanOutcome = ImportOutcome(
            folderId = "f1", added = 2, changed = 1, vanished = 0, revived = 0,
            unchanged = 3, alreadyOnShelf = 0, truncated = false,
        )
        val vm = ShelfViewModel(repo)
        advanceUntilIdle()

        vm.rescanFolder("f1")
        advanceUntilIdle()

        assertEquals(listOf("f1"), repo.rescannedFolders)
        assertEquals("文件夹已重扫：新增 2，更新 1，消失 0", vm.state.value.message)
    }

    @Test
    fun `移除文件夹清掉选择并提示保留历史`() = runTest {
        val repo = FakeRepo(
            rows = listOf(row("a", "f1")),
            folders = listOf(folder("f1", "第一卷")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()
        vm.onFolderSelected("f1")
        advanceUntilIdle()

        vm.removeFolder("f1")
        advanceUntilIdle()

        assertEquals(listOf("f1"), repo.removedFolders)
        assertNull(vm.state.value.selectedFolderId)
        assertEquals("已移除「第一卷」及其 1 本书（收听历史保留）", vm.state.value.message)
    }
}
