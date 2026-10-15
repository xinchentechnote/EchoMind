package com.xinchen.echomind.ui.player

import android.net.Uri
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.RecentSessionRow
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.ImportOutcome
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.PlaybackStateSnapshot
import com.xinchen.echomind.data.repo.PlaybackStats
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.data.repo.SubtitleRepository
import com.xinchen.echomind.data.repo.SubtitleSource
import com.xinchen.echomind.data.repo.SubtitleSummary
import com.xinchen.echomind.domain.model.ListeningSessionData
import com.xinchen.echomind.domain.model.PlaybackProgress
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TranscriptionTarget
import com.xinchen.echomind.domain.playback.QueueItem
import com.xinchen.echomind.domain.subtitle.SubtitleWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放页的三条易碎性质（都在真机上踩过或差点踩到）：
 * 1. 这一首已经在播时不能重开——否则从通知栏点回 App 会把进度扔回起点；
 * 2. 自动切歌之后页头与统计要跟着换成正在播的那一本；
 * 3. 起播点走 [resumePositionFor]：听完了的从头播，没听完的续在记忆里的那个位置。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val bookA = row("a", "三体", durationMs = 30_000, lastPositionMs = 12_000)
    private val bookB = row("b", "第二章", durationMs = 45_000, lastPositionMs = 0)

    private fun row(id: String, title: String, durationMs: Long, lastPositionMs: Long) = ShelfRow(
        id = id,
        uri = "content://audio/$id",
        title = title,
        fileName = "$id.mp3",
        durationMs = durationMs,
        sizeBytes = 1_000_000,
        fileStatus = "OK",
        transcriptionState = "NONE",
        isFavorite = false,
        lastPositionMs = lastPositionMs,
        lastPlayedAtMs = 0,
        playbackSpeed = 1f,
    )

    private class FakeAudioRepo(rows: List<ShelfRow>) : AudioRepository {
        val shelfFlow = MutableStateFlow(rows)
        val favorites = mutableListOf<Pair<String, Boolean>>()

        override fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>> = shelfFlow
        override fun lastPlayed(): Flow<ShelfRow?> = MutableStateFlow(null)
        override fun folders(): Flow<List<ImportedFolderEntity>> = MutableStateFlow(emptyList())
        override suspend fun transcriptionTarget(audioId: String): TranscriptionTarget? = unsupported()
        override suspend fun setFavorite(audioId: String, favorite: Boolean) {
            favorites += audioId to favorite
        }

        override suspend fun importFolder(treeUri: Uri): ImportOutcome = unsupported()
        override suspend fun rescanAll(): List<ImportOutcome> = unsupported()
        override suspend fun rename(audioId: String, title: String) = unsupported()
        override suspend fun removeBook(audioId: String) = unsupported()
        override suspend fun removeFolder(folderId: String) = unsupported()
        override suspend fun markPermissionRevoked(folderId: String) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private class FakePlaybackRepo(
        private val memories: Map<String, PlaybackStateSnapshot>,
    ) : PlaybackRepository {
        override fun snapshot(audioId: String): Flow<PlaybackStateSnapshot> =
            MutableStateFlow(memories[audioId] ?: memory())

        override fun stats(): Flow<PlaybackStats> = MutableStateFlow(PlaybackStats(0, 0, 0))
        override fun recentSessions(limit: Int): Flow<List<RecentSessionRow>> = MutableStateFlow(emptyList())
        override suspend fun openSession(audioId: String, title: String, startPositionMs: Long, durationMs: Long) = unsupported()
        override suspend fun flushProgress(progress: PlaybackProgress, speed: Float) = unsupported()
        override suspend fun settleSession(session: ListeningSessionData, speed: Float) = unsupported()
        override suspend fun saveSpeed(audioId: String, speed: Float) = unsupported()
        override suspend fun recoverCrashedSessions(): Int = unsupported()
        override suspend fun recomputeAggregates() = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private class FakeController(initial: PlaybackUiState = PlaybackUiState()) : PlaybackController {
        override val state = MutableStateFlow(initial)
        val plays = mutableListOf<PlaybackUiState>()
        var lastQueue: List<QueueItem> = emptyList()
        var lastIndex = -1
        var lastStartPosMs = -1L

        override fun play(queue: List<QueueItem>, index: Int, startPosMs: Long) {
            lastQueue = queue
            lastIndex = index
            lastStartPosMs = startPosMs
            plays += state.value
            state.value = state.value.copy(
                audioId = queue[index].audioId,
                title = queue[index].title,
                queue = queue,
                index = index,
                positionMs = startPosMs,
                isPlaying = true,
            )
        }

        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun skip(byMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() {
            state.value = state.value.copy(isPlaying = false)
        }
    }

    private class FakeSubtitleRepo(initial: Map<String, List<SubtitleSegment>> = emptyMap()) : SubtitleRepository {
        val flows = MutableStateFlow(initial)
        val asrIds = mutableSetOf<String>()
        val replaced = mutableListOf<Pair<String, Int>>()

        override fun observeSegments(audioId: String): Flow<List<SubtitleSegment>> =
            flows.map { it[audioId].orEmpty() }

        override fun observeSummary(audioId: String): Flow<SubtitleSummary> =
            flows.map { segments ->
                SubtitleSummary(segments[audioId].orEmpty().size, producedByAsr = audioId in asrIds)
            }

        override suspend fun replace(audioId: String, segments: List<SubtitleSegment>, source: SubtitleSource): Int {
            replaced += audioId to segments.size
            flows.value = flows.value + (audioId to segments)
            return segments.size
        }

        override suspend fun clear(audioId: String): Int {
            flows.value = flows.value - audioId
            return 0
        }
    }

    private fun seg(index: Int, startMs: Long, text: String) =
        SubtitleSegment(index = index, startMs = startMs, endMs = startMs + 1_000, text = text)

    private fun memories(positionA: Long = 12_000, positionB: Long = 0) = mapOf(        "a" to memory(positionMs = positionA, playCount = 3, listenedMsTotal = 40_000),
        "b" to memory(positionMs = positionB, playCount = 7, listenedMsTotal = 80_000),
    )

    @Test
    fun `不在播这一首时按记忆位置起播`() = runTest {
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), FakeSubtitleRepo())
        advanceUntilIdle()

        assertEquals(1, controller.plays.size)
        assertEquals(0, controller.lastIndex)
        assertEquals(12_000, controller.lastStartPosMs)
        assertEquals(listOf("a", "b"), controller.lastQueue.map { it.audioId })
        assertEquals("三体", vm.state.value.title)
        assertEquals(3, vm.state.value.playCount)
    }

    @Test
    fun `这一首已经在播时不重开`() = runTest {
        val controller = FakeController(
            PlaybackUiState(audioId = "a", title = "三体", positionMs = 21_000, isPlaying = true),
        )
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), FakeSubtitleRepo())
        advanceUntilIdle()

        assertEquals(0, controller.plays.size)
        assertEquals(21_000, vm.playback.value.positionMs)
    }

    @Test
    fun `听完过的书再进来从头播`() = runTest {
        val controller = FakeController()
        // 记忆位置就在末尾容差内：说明上次听完了，不该每次进来都卡在 99%
        PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories(positionA = 29_000)), FakeSubtitleRepo())
        advanceUntilIdle()

        assertEquals(0, controller.lastStartPosMs)
    }

    @Test
    fun `自动切歌后页头与统计跟着换成正在播的那一本`() = runTest {
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), FakeSubtitleRepo())
        advanceUntilIdle()
        assertEquals("三体", vm.state.value.title)

        controller.state.value = controller.state.value.copy(audioId = "b", title = "第二章", positionMs = 1_000)
        advanceUntilIdle()

        assertEquals("第二章", vm.state.value.title)
        assertEquals(7, vm.state.value.playCount)
        assertEquals(80_000, vm.state.value.listenedMsTotal)
    }

    @Test
    fun `收藏切换作用于正在播的那一本`() = runTest {
        val audio = FakeAudioRepo(listOf(bookA, bookB))
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, audio, FakePlaybackRepo(memories()), FakeSubtitleRepo())
        advanceUntilIdle()

        controller.state.value = controller.state.value.copy(audioId = "b", title = "第二章")
        advanceUntilIdle()
        vm.toggleFavorite()
        advanceUntilIdle()

        assertEquals(listOf("b" to true), audio.favorites)
    }

    @Test
    fun `书不在书架时标记缺失且不起播`() = runTest {
        val controller = FakeController()
        val vm = PlayerViewModel("zz", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), FakeSubtitleRepo())
        advanceUntilIdle()

        assertEquals(0, controller.plays.size)
        assertEquals(true, vm.state.value.missing)
    }

    @Test
    fun `字幕窗口跟着播放位置更新当前句`() = runTest {
        val subtitles = FakeSubtitleRepo(
            mapOf("a" to listOf(seg(0, 0, "第一句"), seg(1, 5_000, "第二句"), seg(2, 10_000, "第三句"))),
        )
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), subtitles)
        advanceUntilIdle()

        // 起播点 12s（记忆位置）落在第三句
        assertEquals(2, vm.subtitles.value.currentIndex)
        assertEquals("第三句", vm.subtitles.value.current?.text)

        controller.state.value = controller.state.value.copy(positionMs = 5_500)
        advanceUntilIdle()
        assertEquals("第二句", vm.subtitles.value.current?.text)
        assertEquals(3, vm.subtitleSummary.value.count)
        assertEquals(false, vm.subtitleSummary.value.producedByAsr)
    }

    @Test
    fun `同一句内部的位置推进不重发窗口`() = runTest {
        val subtitles = FakeSubtitleRepo(
            mapOf("a" to listOf(seg(0, 0, "第一句"), seg(1, 10_000, "第二句"), seg(2, 20_000, "第三句"))),
        )
        val controller = FakeController(PlaybackUiState(audioId = "a", title = "三体", positionMs = 1_000, isPlaying = true))
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), subtitles)

        val seen = mutableListOf<SubtitleWindow>()
        val watching = launch { vm.subtitles.collect { seen += it } }
        advanceUntilIdle()
        val afterStart = seen.size
        assertEquals(0, vm.subtitles.value.currentIndex)

        // 500ms tick 在同一句里连推 5 次：窗口内容一个字都没变，UI 就不该收到新值（FR-707）
        repeat(5) { step ->
            controller.state.value = controller.state.value.copy(positionMs = 2_000L + step * 1_000)
            advanceUntilIdle()
        }
        assertEquals(afterStart, seen.size)

        controller.state.value = controller.state.value.copy(positionMs = 10_000)
        advanceUntilIdle()
        assertEquals(afterStart + 1, seen.size)
        assertEquals(1, seen.last().currentIndex)
        watching.cancel()
    }

    @Test
    fun `切歌后字幕跟着换成新歌的那一套`() = runTest {
        val subtitles = FakeSubtitleRepo(
            mapOf(
                "a" to listOf(seg(0, 0, "A 的第一句")),
                "b" to listOf(seg(0, 0, "B 的第一句"), seg(1, 2_000, "B 的第二句")),
            ),
        )
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), subtitles)
        advanceUntilIdle()
        assertEquals("A 的第一句", vm.subtitles.value.current?.text)

        controller.state.value = controller.state.value.copy(audioId = "b", title = "第二章", positionMs = 2_500)
        advanceUntilIdle()

        assertEquals("B 的第二句", vm.subtitles.value.current?.text)
        assertEquals(1, vm.subtitles.value.currentIndex)
    }

    @Test
    fun `导入测试字幕写进正在播的这一首并标明来源`() = runTest {
        val subtitles = FakeSubtitleRepo()
        val controller = FakeController()
        val vm = PlayerViewModel("a", controller, FakeAudioRepo(listOf(bookA, bookB)), FakePlaybackRepo(memories()), subtitles)
        advanceUntilIdle()
        assertEquals(0, vm.subtitleSummary.value.count)

        vm.importTestSubtitles("1\n00:00:00,000 --> 00:00:02,000\n念出来的第一句\n\n")
        advanceUntilIdle()

        assertEquals(listOf("a" to 1), subtitles.replaced)
        assertEquals(1, vm.subtitleSummary.value.count)
        assertEquals("念出来的第一句", vm.subtitles.value.current?.text)
        assertTrue(vm.subtitleNotice.value!!.contains("非 ASR 产物"))

        vm.importTestSubtitles("这不是字幕文件")
        advanceUntilIdle()
        assertTrue(vm.subtitleNotice.value!!.startsWith("导入失败"))
    }
}


/** 播放记忆行；nested fake 不是 inner class，所以这个构造器放在文件顶层给两边共用 */
private fun memory(positionMs: Long = 0, playCount: Int = 0, listenedMsTotal: Long = 0) = PlaybackStateSnapshot(
    positionMs = positionMs,
    durationMs = 0,
    speed = 1f,
    playCount = playCount,
    completedCount = 0,
    listenedMsTotal = listenedMsTotal,
    lastPlayedAtMs = 0,
    finished = false,
)
