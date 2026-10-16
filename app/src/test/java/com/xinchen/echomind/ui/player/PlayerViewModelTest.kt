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
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptionTarget
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.playback.QueueItem
import com.xinchen.echomind.domain.subtitle.SubtitleWindow
import com.xinchen.echomind.data.repo.WmaPlaybackStore
import com.xinchen.echomind.domain.transcript.LiveTranscriptStore
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * 播放页的易碎性质（都在真机上踩过或差点踩到）：
 * 1. 这一首已经在播时不能重开——否则从通知栏点回 App 会把进度扔回起点；
 * 2. 自动切歌之后页头与统计要跟着换成正在播的那一本；
 * 3. 起播点走 [resumePositionFor]：听完了的从头播，没听完的续在记忆里的那个位置；
 * 4. 「首次播放自动转录」只在三无（没转过、没有任务行、没有任何字幕）时发起，
 *    且转录中的实时预览要跟随播放位置、失败要给人话文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

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
        override fun seekTo(positionMs: Long) {
            state.value = state.value.copy(positionMs = positionMs)
        }
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

    private class FakeTranscriptRepo(rows: List<TranscriptJobRow> = emptyList()) : TranscriptRepository {
        val jobsFlow = MutableStateFlow(rows)

        override fun jobs(): Flow<List<TranscriptJobRow>> = jobsFlow
        override fun observeActiveJob(audioId: String): Flow<TranscriptJobRow?> =
            jobsFlow.map { list -> list.firstOrNull { it.audioId == audioId } }

        override suspend fun enqueue(audioId: String, model: TranscriptModelRef, force: Boolean): EnqueueOutcome =
            unsupported()
        override suspend fun cancel(jobId: String): Boolean = unsupported()
        override suspend fun attachWorkRequest(jobId: String, workRequestId: String): Boolean = unsupported()
        override suspend fun jobById(jobId: String): TranscriptJobRow? = unsupported()
        override suspend fun retry(jobId: String, model: TranscriptModelRef): EnqueueOutcome = unsupported()
        override suspend fun markRunning(jobId: String, totalSegments: Int): Boolean = unsupported()
        override suspend fun updateProgress(jobId: String, doneSegments: Int, totalSegments: Int): Boolean = unsupported()
        override suspend fun commitSubtitles(jobId: String, segments: List<SubtitleSegment>): Int = unsupported()
        override suspend fun markFailed(jobId: String, failure: TranscriptFailure, message: String?): Boolean = unsupported()
        override suspend fun recoverInterrupted(): Int = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private class Harness(
        val controller: FakeController = FakeController(),
        val audio: FakeAudioRepo = FakeAudioRepo(listOf(bookA, bookB)),
        val subtitles: FakeSubtitleRepo = FakeSubtitleRepo(),
        val transcript: FakeTranscriptRepo = FakeTranscriptRepo(),
        val liveStore: LiveTranscriptStore = LiveTranscriptStore(),
        val audioId: String = "a",
    ) {
        val started = mutableListOf<String>()
        val vm: PlayerViewModel = PlayerViewModel(
            audioId,
            controller,
            audio,
            FakePlaybackRepo(memories()),
            subtitles,
            transcript,
            liveStore,
            startTranscription = { started += it },
            // 与生产同一个约定类：wma 书换转码产物 URI，其余原样
            playbackUriOf = { row ->
                WmaPlaybackStore(java.io.File("/tmp/echomind-test-transcoded"))
                    .playbackUriFor(row.fileName, row.uri, row.id)
            },
        )
    }

    // -- 既有性质 ------------------------------------------------------------------

    @Test
    fun `不在播这一首时按记忆位置起播`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        assertEquals(1, h.controller.plays.size)
        assertEquals(0, h.controller.lastIndex)
        assertEquals(12_000, h.controller.lastStartPosMs)
        assertEquals(listOf("a", "b"), h.controller.lastQueue.map { it.audioId })
        assertEquals("三体", h.vm.state.value.title)
        assertEquals(3, h.vm.state.value.playCount)
    }

    @Test
    fun `这一首已经在播时不重开`() = runTest {
        val h = Harness(
            controller = FakeController(
                PlaybackUiState(audioId = "a", title = "三体", positionMs = 21_000, isPlaying = true),
            ),
        )
        advanceUntilIdle()

        assertEquals(0, h.controller.plays.size)
        assertEquals(21_000, h.vm.playback.value.positionMs)
    }

    @Test
    fun `听完过的书再进来从头播`() = runTest {
        val controller = FakeController()
        PlayerViewModel(
            "a", controller,
            FakeAudioRepo(listOf(bookA, bookB)),
            FakePlaybackRepo(memories(positionA = 29_000)),
            FakeSubtitleRepo(), FakeTranscriptRepo(), LiveTranscriptStore(),
            startTranscription = { },
            playbackUriOf = { row -> row.uri },
        )
        advanceUntilIdle()

        assertEquals(0, controller.lastStartPosMs)
    }

    // -- 首次播放自动转录 ------------------------------------------------------------

    @Test
    fun `wma书播放队列用的是转码产物uri`() = runTest {
        val h = Harness(
            audio = FakeAudioRepo(listOf(row("w", "WMA 书", 30_000, 0, fileName = "w.wma"), bookB)),
            audioId = "w",
        )
        advanceUntilIdle()

        assertEquals("file:/tmp/echomind-test-transcoded/w.m4a", h.controller.lastQueue.first { it.audioId == "w" }.uri)
    }

    @Test
    fun `非wma书播放队列保持原uri`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        assertEquals("content://audio/a", h.controller.lastQueue.first { it.audioId == "a" }.uri)
    }

    @Test
    fun `首次播放三无的书自动发起转录`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        assertEquals(listOf("a"), h.started)
    }

    @Test
    fun `转过取消过有任务行就不再自动发起`() = runTest {
        val h = Harness(
            transcript = FakeTranscriptRepo(
                listOf(
                    TranscriptJobRow(
                        id = "j1", audioId = "a", modelId = "m", modelVersion = "v",
                        status = TranscriptJobStatus.Failed, totalSegments = 0, doneSegments = 0,
                        failure = TranscriptFailure.Cancelled, errorMessage = "用户取消",
                        createdAtMs = 1, startedAtMs = 2, finishedAtMs = 3,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `已就绪的书不再自动发起`() = runTest {
        val h = Harness(
            audio = FakeAudioRepo(listOf(row("a", "三体", 30_000, 0, state = "READY"), bookB)),
        )
        advanceUntilIdle()

        assertTrue(h.started.isEmpty())
    }

    @Test
    fun `有测试字幕的书不自动发起不悄悄顶掉`() = runTest {
        val h = Harness(subtitles = FakeSubtitleRepo(mapOf("a" to listOf(seg(0, 0, "测试句")))))
        advanceUntilIdle()

        assertTrue(h.started.isEmpty())
    }

    // -- 转录中的实时显示 --------------------------------------------------------------

    private fun runningJob(audioId: String, done: Int, total: Int = 0) = TranscriptJobRow(
        id = "j-$audioId", audioId = audioId, modelId = "m", modelVersion = "v",
        status = TranscriptJobStatus.Running, totalSegments = total, doneSegments = done,
        failure = null, errorMessage = null, createdAtMs = 1, startedAtMs = 2, finishedAtMs = null,
    )

    @Test
    fun `转录中的状态行给段数与句数`() = runTest {
        val h = Harness(transcript = FakeTranscriptRepo(listOf(runningJob("a", done = 3))))
        h.liveStore.append("a", listOf(seg(0, 1_000, "甲"), seg(1, 2_000, "乙")), doneSegments = 3)
        advanceUntilIdle()

        val line = h.vm.liveTranscript.value.statusLine
        assertTrue("$line", line!!.contains("已识别 3 段"))
        assertTrue(line.contains("已出 2 句"))
        assertTrue(line.contains("正在转录"))
        assertTrue(h.vm.liveTranscript.value.running)
    }

    @Test
    fun `实时窗口跟随播放位置高亮当前句`() = runTest {
        val h = Harness()
        h.liveStore.append("a", listOf(seg(0, 1_000, "甲"), seg(1, 2_000, "乙")), doneSegments = 1)
        advanceUntilIdle()

        h.controller.seekTo(2_500)
        advanceUntilIdle()

        val window = h.vm.liveWindow.value
        assertEquals("乙", window!!.current?.text)
        // 库里还没有字幕，这个窗口就是实时预览
        assertEquals(0, h.subtitles.flows.value.size)
    }

    @Test
    fun `收尾清空预览后实时窗口退位`() = runTest {
        val h = Harness()
        h.liveStore.append("a", listOf(seg(0, 1_000, "甲")), doneSegments = 1)
        advanceUntilIdle()
        assertEquals("甲", h.vm.liveWindow.value!!.current?.text)

        h.liveStore.clear("a")
        advanceUntilIdle()

        assertNull(h.vm.liveWindow.value)
        assertNull(h.vm.liveTranscript.value.statusLine)
    }

    @Test
    fun `失败的任务给人话文案`() = runTest {
        val h = Harness(
            transcript = FakeTranscriptRepo(
                listOf(
                    TranscriptJobRow(
                        id = "j1", audioId = "a", modelId = "m", modelVersion = "v",
                        status = TranscriptJobStatus.Failed, totalSegments = 0, doneSegments = 0,
                        failure = TranscriptFailure.ModelMissing, errorMessage = "模型不可用",
                        createdAtMs = 1, startedAtMs = 2, finishedAtMs = 3,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val line = h.vm.liveTranscript.value.failureLine!!
        assertTrue(line, line.contains("转录未完成"))
        assertTrue(line, line.contains("从安装包恢复"))
    }

    // -- 状态行纯函数 ------------------------------------------------------------------

    @Test
    fun `分母未知不报百分比`() {
        val line = liveStatusLine(runningJob("a", done = 4, total = 0), sentenceCount = 2)
        assertTrue("$line", line!!.contains("已识别 4 段"))
        assertTrue(!line.contains("/"))
        assertTrue(!line.contains("%"))
    }

    @Test
    fun `分母已知报进度排队中不报段数`() {
        assertTrue(liveStatusLine(runningJob("a", done = 3, total = 10), 1)!!.contains("已识别 3/10 段"))
        val queued = liveStatusLine(
            TranscriptJobRow(
                id = "j", audioId = "a", modelId = "m", modelVersion = "v",
                status = TranscriptJobStatus.Queued, totalSegments = 0, doneSegments = 0,
                failure = null, errorMessage = null, createdAtMs = 1, startedAtMs = null, finishedAtMs = null,
            ),
            sentenceCount = 0,
        )!!
        assertTrue(queued.contains("排队"))
        assertTrue(!queued.contains("段"))
        assertNull(liveStatusLine(null, 5))
    }
}

// 顶层假数据：嵌套类（Harness、Fake*）拿不到外部类的成员接收者，所以这些必须是文件级

private val bookA = row("a", "三体", durationMs = 30_000, lastPositionMs = 12_000)
private val bookB = row("b", "第二章", durationMs = 45_000, lastPositionMs = 0)

private fun row(
    id: String,
    title: String,
    durationMs: Long,
    lastPositionMs: Long,
    state: String = "NONE",
    fileName: String = "$id.mp3",
) = ShelfRow(
    id = id,
    uri = "content://audio/$id",
    title = title,
    fileName = fileName,
    durationMs = durationMs,
    sizeBytes = 1_000_000,
    fileStatus = "OK",
    transcriptionState = state,
    isFavorite = false,
    lastPositionMs = lastPositionMs,
    lastPlayedAtMs = 0,
    playbackSpeed = 1f,
)

private fun seg(index: Int, startMs: Long, text: String) =
    SubtitleSegment(index = index, startMs = startMs, endMs = startMs + 1_000, text = text)

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

private fun memories(positionA: Long = 12_000, positionB: Long = 0) = mapOf(
    "a" to memory(positionMs = positionA, playCount = 3, listenedMsTotal = 40_000),
    "b" to memory(positionMs = positionB, playCount = 7, listenedMsTotal = 80_000),
)
