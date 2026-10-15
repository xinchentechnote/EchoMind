package com.xinchen.echomind.ui.shelf

import android.net.Uri
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ImportOutcome
import com.xinchen.echomind.data.repo.ShelfTab
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
import org.junit.Before
import org.junit.Test

/**
 * 回归：曾经 tab/query 存在两份状态里，点「收藏」后列表换了但 `state.tab` 仍是 All，
 * 于是 TabRow 高亮不动、空态文案也读错。这里钉住「状态与查询条件同源」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo : AudioRepository {
        val requests = mutableListOf<Pair<ShelfTab, String>>()

        override fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>> {
            requests += tab to query
            return MutableStateFlow(emptyList())
        }

        override fun lastPlayed(): Flow<ShelfRow?> = MutableStateFlow(null)
        override fun folders(): Flow<List<ImportedFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun transcriptionTarget(audioId: String): TranscriptionTarget? = unsupported()
        override suspend fun importFolder(treeUri: Uri): ImportOutcome = unsupported()
        override suspend fun rescanAll(): List<ImportOutcome> = unsupported()
        override suspend fun rename(audioId: String, title: String) = unsupported()
        override suspend fun setFavorite(audioId: String, favorite: Boolean) = unsupported()
        override suspend fun removeBook(audioId: String) = unsupported()
        override suspend fun removeFolder(folderId: String) = unsupported()
        override suspend fun markPermissionRevoked(folderId: String) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

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
}
