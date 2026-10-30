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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归：曾经 tab/query 存在两份状态里，点「收藏」后列表换了但 `state.tab` 仍是 All，
 * 于是 TabRow 高亮不动、空态文案也读错。这里钉住「状态与查询条件同源」。
 *
 * 目录浏览（2026-10-28，用户口径「先见有内容的文件夹，逐层进去，不要直接平铺全部」）：
 * 「全部」且无搜索词时 browser 非 null；根层只列有内容的文件夹；夹内按相对路径分层，
 * 本机伪文件夹的书要先剥掉设备目录前缀。
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

    private fun row(id: String, folderId: String, relativePath: String, title: String = "书$id") = ShelfRow(
        id = id, uri = "content://x/$id", title = title, fileName = "$id.mp3", relativePath = relativePath,
        durationMs = 60_000, sizeBytes = 1, fileStatus = "PRESENT", transcriptionState = "NONE",
        isFavorite = false, lastPositionMs = 0, lastPlayedAtMs = 0, playbackSpeed = 1f, folderId = folderId,
    )

    private fun folder(id: String, name: String, treeUri: String = "tree://$id") = ImportedFolderEntity(
        id = id, treeUri = treeUri, displayName = name, grantedAtMs = 1,
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
    fun `全部tab根层只列有内容的文件夹且计数正确`() = runTest {
        val repo = FakeRepo(
            rows = listOf(row("a", "f1", "x.mp3"), row("b", "f1", "y.mp3"), row("c", "f2", "z.mp3")),
            folders = listOf(folder("f1", "甲夹"), folder("f2", "乙夹"), folder("f3", "空夹")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()

        val root = vm.state.value.browser
        assertTrue(root is ShelfBrowser.Root)
        // 空夹不出现（用户口径：展示**有内容**的文件夹）；按显示名排序
        assertEquals(listOf("乙夹" to 1, "甲夹" to 2), (root as ShelfBrowser.Root).folders.map { it.displayName to it.count })
        assertEquals(3, vm.state.value.rows.size)
    }

    @Test
    fun `进入文件夹后按内层相对路径分层且剥掉设备前缀`() = runTest {
        val repo = FakeRepo(
            rows = listOf(
                row("a", "f1", "Music/有声书/上册/a.mp3"),
                row("b", "f1", "Music/有声书/下册/b.mp3"),
                row("c", "f1", "Music/有声书/direct.mp3"),
            ),
            folders = listOf(folder("f1", "有声书", treeUri = "mediastore://external_primary/Music/有声书")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()

        vm.browseInto("f1")
        advanceUntilIdle()

        val inside = vm.state.value.browser as ShelfBrowser.Inside
        assertEquals("有声书", inside.folderName)
        assertEquals(listOf("上册" to 1, "下册" to 1), inside.folders.map { it.displayName to it.fileCount })
        assertEquals(listOf("c"), inside.books.map { it.id })

        vm.browseIntoSub("上册")
        advanceUntilIdle()
        val sub = vm.state.value.browser as ShelfBrowser.Inside
        assertEquals("上册", sub.path)
        assertEquals(listOf("a"), sub.books.map { it.id })

        vm.browseUp()
        advanceUntilIdle()
        assertEquals("", (vm.state.value.browser as ShelfBrowser.Inside).path)

        vm.browseUp()
        advanceUntilIdle()
        assertTrue(vm.state.value.browser is ShelfBrowser.Root)
    }

    @Test
    fun `搜索时回平铺目录浏览被挂起`() = runTest {
        val repo = FakeRepo(
            rows = listOf(row("a", "f1", "x.mp3")),
            folders = listOf(folder("f1", "甲夹")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()
        vm.browseInto("f1")
        advanceUntilIdle()
        assertTrue(vm.state.value.browser is ShelfBrowser.Inside)

        vm.onQueryChanged("书a")
        advanceUntilIdle()
        assertNull(vm.state.value.browser)
        // 平铺行仍可用（搜索结果）
        assertEquals(listOf("a"), vm.state.value.rows.map { it.id })

        vm.onQueryChanged("")
        advanceUntilIdle()
        // 清掉搜索词回到原浏览位置
        assertTrue(vm.state.value.browser is ShelfBrowser.Inside)
    }

    @Test
    fun `按文件夹重扫给出单夹结果`() = runTest {
        val repo = FakeRepo(folders = listOf(folder("f1", "甲夹")))
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
    fun `移除文件夹给出保留历史的提示`() = runTest {
        val repo = FakeRepo(
            rows = listOf(row("a", "f1", "x.mp3")),
            folders = listOf(folder("f1", "甲夹")),
        )
        val vm = ShelfViewModel(repo)
        repo.emit()
        advanceUntilIdle()

        vm.removeFolder("f1")
        advanceUntilIdle()

        assertEquals(listOf("f1"), repo.removedFolders)
        assertEquals("已移除「甲夹」及其 1 本书（收听历史保留）", vm.state.value.message)
    }
}
