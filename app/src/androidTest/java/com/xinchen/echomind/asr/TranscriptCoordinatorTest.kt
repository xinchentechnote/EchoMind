package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.SubtitleSegmentEntity
import com.xinchen.echomind.data.db.TranscriptJobEntity
import com.xinchen.echomind.data.repo.AudioRepositoryImpl
import com.xinchen.echomind.data.repo.TreeGrantStore
import com.xinchen.echomind.data.repo.TranscriptRepositoryImpl
import com.xinchen.echomind.data.repo.WmaPlaybackStore
import com.xinchen.echomind.data.scan.AudioMetadataReader
import com.xinchen.echomind.domain.importing.TreeBrowser
import com.xinchen.echomind.domain.importing.TreeEntry
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptPhase
import com.xinchen.echomind.domain.model.TranscriptProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 协调器的设备端到端（`docs/asr-design.md` §5，Phase 6 第 4b 项的验收）。
 *
 * 前面几类测试各自钉住一环（解码、VAD、识别、切句、状态机），**没有任何一条把六环串起来写过库**。
 * 这条补上的是本项目最要紧的一句话：「书架上那本书的字幕，是真模型跑出来的，没有一个字是编排层造的」
 * （AGENTS.md §5）。所以断言全部落在**库里的行**上，不是落在返回值上。
 *
 * 失败路径按「花不花钱」分两拨：
 * - 走不到 `SherpaOnnxEngine.open` 的（状态守卫、缺文件、缺授权、缺模型）一条秒级都不该花；
 * - 必须真开引擎的（静音素材、解码器打不开）各多装一次 243 MB 模型 —— 装载实测约 5 s，
 *   这是「先核对模型再解码」这个顺序的代价，不改产品代码去绕它。
 *
 * 素材与模型都由宿主机搬上来（缺了直接 fail 并打印命令，见 [DeviceAsrFixtures]），不 `assumeTrue` 跳过。
 */
@RunWith(AndroidJUnit4::class)
class TranscriptCoordinatorTest {

    private companion object {
        const val TAG = "CoordinatorTest"
        const val AUDIO_ID = "a"

        /**
         * 每调一次前进这么多毫秒。[PlaybackPolicy.TRANSCRIPT_PROGRESS_INTERVAL_MS] 是 2 s，
         * 假时钟要是静止不动，`reportProgress` 一段都不会报出去，进度那几条断言就无从检查。
         */
        const val CLOCK_STEP_MS = 60_000L
    }

    /** 墙钟与单调钟都不走：库里的 created_at 要能预测 */
    private class FixedClock(var now: Long = 1_700_000_000_000L) : TimeSource {
        override fun monotonicMs(): Long = now
        override fun wallMs(): Long = now
    }

    /** 只给协调器用：单调钟每次调用前进，墙钟保持不动（`vadModelFile` 只把它当核对时间记下） */
    private class SteppingClock : TimeSource {
        private var monotonic = 0L
        override fun monotonicMs(): Long {
            monotonic += CLOCK_STEP_MS
            return monotonic
        }

        override fun wallMs(): Long = 1_700_000_000_000L
    }

    private class FakeGrants(var granted: MutableSet<String> = mutableSetOf()) : TreeGrantStore {
        override fun persistRead(treeUri: String): String? {
            granted += treeUri
            return null
        }

        override fun hasReadGrant(treeUri: String): Boolean = treeUri in granted
        override fun grantedTreeUris(): List<String> = granted.toList()
    }

    private class UnusedBrowser : TreeBrowser {
        override fun children(dirUri: String): List<TreeEntry> = emptyList()
        override fun treeDisplayName(treeUri: String): String? = null
    }

    private class UnusedReader : AudioMetadataReader {
        override fun durationMs(uri: String): Long? = null
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EchoMindDatabase
    private lateinit var clock: FixedClock
    private lateinit var repository: TranscriptRepositoryImpl
    private lateinit var grants: FakeGrants
    private lateinit var audioRepository: AudioRepositoryImpl

    /** 生产路径同一个位置：协调器核对的就是设备上那份真模型（`files/models/`） */
    private val modelsRoot: File by lazy { DeviceAsrFixtures.modelRoot(context) }

    @Before
    fun setUp() = runBlocking {
        clock = FixedClock()
        db = Room.inMemoryDatabaseBuilder(context, EchoMindDatabase::class.java).build()
        repository = TranscriptRepositoryImpl(db, clock)
        grants = FakeGrants()
        audioRepository = AudioRepositoryImpl(
            audioBookDao = db.audioBookDao(),
            folderDao = db.importedFolderDao(),
            shelfDao = db.shelfDao(),
            treeBrowser = UnusedBrowser(),
            metadataReader = UnusedReader(),
            grants = grants,
            nowMs = { clock.wallMs() },
        )
        db.importedFolderDao().upsert(
            ImportedFolderEntity(id = "folder-1", treeUri = "tree://music", displayName = "Music", grantedAtMs = 1L),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun coordinator(
        root: File = modelsRoot,
        wmaStore: WmaPlaybackStore? = null,
        onWmaPrepared: suspend (String, String, Long) -> Unit = { _, _, _ -> },
    ) = TranscriptCoordinator(
        context, audioRepository, grants, repository, root, SteppingClock(),
        wmaStore = wmaStore, onWmaPrepared = onWmaPrepared,
    )

    /** 直接摆行（不走导入编排，那条路径有 AudioRepositoryImportTest 守着），然后入队拿 jobId */
    private suspend fun enqueueBook(
        uri: String,
        audioId: String = AUDIO_ID,
        folderId: String = "folder-1",
        fileStatus: String = AudioBookEntity.FileStatus.Present,
        durationMs: Long = 0,
        modelRef: TranscriptModelRef = ModelCatalog.transcriptRef,
        fileName: String = "a.mp3",
    ): String {
        db.audioBookDao().upsert(
            AudioBookEntity(
                id = audioId,
                uri = uri,
                folderId = folderId,
                relativePath = fileName,
                fileName = fileName,
                title = "那些花儿",
                sizeBytes = 1,
                fileLastModifiedMs = 1,
                durationMs = durationMs,
                fileStatus = fileStatus,
                addedAtMs = 1,
                lastScannedAtMs = 1,
                updatedAtMs = 1,
            ),
        )
        return (repository.enqueue(audioId, modelRef) as EnqueueOutcome.Created).jobId
    }

    private suspend fun jobRow(id: String): TranscriptJobEntity = db.transcriptJobDao().getById(id)!!
    private suspend fun bookState(): String = db.audioBookDao().getById(AUDIO_ID)!!.transcriptionState
    private suspend fun subtitles(): List<SubtitleSegmentEntity> = db.subtitleSegmentDao().getByAudio(AUDIO_ID)

    private fun fileUriOf(name: String): String = Uri.fromFile(DeviceAsrFixtures.fixture(name, context)).toString()

    @Test
    fun 真模型跑完整首歌后字幕第一次进了数据库() = runBlocking {
        // 时长从设备上那份素材的容器里读（素材换过版本，写死常量会把「字幕越界」断言变成素材对账）
        val durationMs = PcmDecoder.containerDurationMs(context, Uri.parse(fileUriOf(DeviceAsrFixtures.REAL_MP3)))
        assertTrue("素材读不到容器时长：$durationMs", durationMs > 0)
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_MP3), durationMs = durationMs)
        val reported = mutableListOf<TranscriptProgress>()
        val startedAt = SystemClock.elapsedRealtime()

        val outcome = coordinator().run(jobId) { progress -> reported += progress }
        val wallMs = SystemClock.elapsedRealtime() - startedAt

        val committed = outcome as? TranscriptOutcome.Committed
            ?: error("端到端没跑成（$outcome）。这条测试要的是「模型的字进库」，不许降级")
        val written = subtitles()
        Log.i(
            TAG,
            "端到端：${committed.segments} 段 → ${committed.sentences} 句（丢弃块 ${committed.rejectedChunks}、" +
                "硬切边界 ${committed.guessedBoundaries}），wall=${wallMs}ms，进度上报 ${reported.size} 次，末次 ${reported.lastOrNull()}",
        )

        assertTrue("一句都没有：$committed", committed.sentences > 0)
        assertEquals(committed.sentences.toLong(), written.size.toLong())

        // FR-601：库里「索引序 = 时间序」是 SubtitleSynchronizer 二分查找的前提，DAO 就是按 index 排的
        assertEquals((0 until written.size).toList(), written.map { it.segmentIndex })
        val starts = written.map { it.startMs }
        assertTrue("时间序与索引序不一致：$starts", starts.zipWithNext().all { (a, b) -> b >= a })

        for (row in written) {
            assertTrue("句时长必须为正：$row", row.endMs > row.startMs)
            assertTrue("空行不算字幕：$row", row.text.isNotBlank())
            assertEquals(SubtitleSegmentEntity.Source.ASR, row.source)
            assertEquals(jobId, row.jobId)
            // 时间必须落在音频以内：单位搞错（秒当毫秒会乘 1000）或按字数均分都会在这里露出来
            assertTrue("字幕越界（超出音频时长 $durationMs ms）：$row", row.endMs <= durationMs + 1_000)
        }

        // 进度是时间口径：分母是容器时长（mp3 一开始就读得到），分子是已解到的位置，单调且不越界
        assertTrue("一次进度都没上报（节流把回调全吃了？）", reported.isNotEmpty())
        assertTrue("mp3 不是 WMA，不该出现转码阶段：$reported", reported.all { it.phase == TranscriptPhase.Recognizing })
        assertTrue("分母该是容器时长：${reported.first()}", reported.all { Math.abs(it.durationMs - durationMs) < 1_000 })
        assertTrue("位置必须单调：$reported", reported.map { it.positionMs }.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("位置不该超过时长太多：${reported.last()}", reported.last().positionMs <= durationMs + 1_000)
        assertTrue("段数必须单调：$reported", reported.map { it.doneSegments }.zipWithNext().all { (a, b) -> b >= a })

        val row = jobRow(jobId)
        assertEquals(TranscriptJobEntity.Status.Success, row.status)
        assertNull(row.errorCode)
        assertNotNull(row.startedAtMs)
        assertNotNull(row.finishedAtMs)
        // 收尾时把分母补成实跑段数：句数（${committed.sentences}）比段数小，用它当分母就是假进度
        assertEquals(committed.segments, row.totalSegments)
        assertEquals(committed.segments, row.doneSegments)
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())

        val uiRow = repository.jobById(jobId)!!
        assertEquals(TranscriptJobStatus.Success, uiRow.status)
        // 终态不再有百分比（进度只属于 RUNNING），UI 对成功行显示的是「共 N 段全部识别」
        assertNull(uiRow.progress)
        assertNull(uiRow.phase)
    }

    /** 同一行不该跑第二遍：跑一遍要烧掉整首的 CPU，还会把已入库的字幕整本换掉 */
    @Test
    fun 正在跑的行不会被第二个worker重复跑() = runBlocking {
        val jobId = enqueueBook("file:///never-decoded.mp3")
        repository.markRunning(jobId, totalSegments = 0)
        assertEquals(TranscriptJobEntity.Status.Running, jobRow(jobId).status)

        val outcome = coordinator().run(jobId)
        assertTrue("RUNNING 的行竟然又跑了一遍：$outcome", outcome is TranscriptOutcome.Skipped)
        assertEquals(TranscriptJobEntity.Status.Running, jobRow(jobId).status)
        assertEquals(0, subtitles().size)
    }

    /**
     * D36 的协调器半边：**别的书**占着车道时，本轮必须在 `SherpaOnnxEngine.open()` 之前收手，
     * 否则同一进程就是两份 243 MB 的 int8 模型（AGENTS.md §3）。
     * 「之前」这件事由 `started_at_ms` 为空证明——`markRunning` 就排在装载前面，抢不到车道就没有开始时间。
     */
    @Test
    fun 车道满时第四本书的协调器报LaneFull且不写字幕() = runBlocking {
        // 先把 N 条车道占满（N = ASR_MAX_CONCURRENT_JOBS）：这些行只需要是 RUNNING，不必真解码；
        // `audio_book.uri` 上有唯一索引，所以每本书换一个占位 uri
        val lanes = (1..AudioFormats.ASR_MAX_CONCURRENT_JOBS).map { n ->
            enqueueBook("file:///lane-$n.mp3", audioId = "lane-$n").also { id ->
                assertTrue("第 $n 条车道该能进", repository.markRunning(id, totalSegments = 0))
            }
        }

        // 第 N+1 本：静音负例只是「另一本书」的占位，本轮根本走不到解码——收手在装载模型之前
        val extra = enqueueBook(fileUriOf(DeviceAsrFixtures.SILENT_WAV), audioId = "b")
        val outcome = coordinator().run(extra)
        val laneFull = outcome as? TranscriptOutcome.LaneFull
            ?: error("第 ${AudioFormats.ASR_MAX_CONCURRENT_JOBS + 1} 本书竟然跑起来了（车道闸失效）：$outcome")
        assertEquals(AudioFormats.ASR_MAX_CONCURRENT_JOBS, laneFull.running)

        val row = jobRow(extra)
        assertEquals("车道满时行必须留在 QUEUED（worker 据此 retry）", TranscriptJobEntity.Status.Queued, row.status)
        assertNull("没抢到车道不该有开始时间：$row", row.startedAtMs)
        assertEquals(0, db.subtitleSegmentDao().getByAudio("b").size)
        // 正在跑的那几行不受影响：别人收手不该顺手把正在跑的那轮结算掉
        lanes.forEach { assertEquals(TranscriptJobEntity.Status.Running, jobRow(it).status) }
    }

    /**
     * D46：多路并发**共享同一份引擎**。两本书（一本 mp3、一本 WMA）同时跑完都入库，而模型只装了一次——
     * 这就是「3 个任务同时」不等于「3 份 243 MB」的证据。用同一个协调器实例，和生产里的单例一样。
     */
    @Test
    fun 两本书并发转录共享同一份引擎都能入库() = runBlocking {
        val mp3Job = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_MP3), audioId = "a", fileName = "a.mp3")
        val wmaJob = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_WMA), audioId = "b", fileName = "b.wma")
        val engines = SharedAsrEngines(modelsRoot)
        val store = WmaPlaybackStore(File(context.cacheDir, "coordinator-concurrent-${System.nanoTime()}"))
        val coordinator = TranscriptCoordinator(
            context, audioRepository, grants, repository, modelsRoot, SteppingClock(),
            wmaStore = store, engines = engines,
        )

        val startedAt = SystemClock.elapsedRealtime()
        val outcomes = listOf(mp3Job, wmaJob).map { jobId ->
            async(Dispatchers.IO) { coordinator.run(jobId) }
        }.awaitAll()
        Log.i(TAG, "并发两路：$outcomes，wall=${SystemClock.elapsedRealtime() - startedAt} ms，引擎装载 ${engines.opened} 次")

        outcomes.forEach { assertTrue("并发里有一路没跑成：$it", it is TranscriptOutcome.Committed) }
        assertEquals("两路共用一份模型，只该装载一次", 1, engines.opened)
        assertEquals("跑完必须把租约都还回去", 0, engines.activeLeases(ModelCatalog.paraformer))
        assertEquals(TranscriptJobEntity.Status.Success, jobRow(mp3Job).status)
        assertEquals(TranscriptJobEntity.Status.Success, jobRow(wmaJob).status)
        assertTrue(db.subtitleSegmentDao().getByAudio("a").isNotEmpty())
        assertTrue(db.subtitleSegmentDao().getByAudio("b").isNotEmpty())
        assertTrue(store.isReady("b", "mp3"))
    }

    /**
     * 进程被杀后 WorkManager 会重跑 worker，而启动时的 `recoverInterrupted()` 已把这行结算成 FAILED。
     * 复活的那次必须一行都不写，否则用户会看到「中断」和「成功」同时存在。
     */
    @Test
    fun 重启后复活的worker不再覆盖中断结论() = runBlocking {
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_MP3))
        assertEquals(1, repository.recoverInterrupted())

        val outcome = coordinator().run(jobId)
        assertTrue("复活 worker 竟然去跑推理：$outcome", outcome is TranscriptOutcome.Skipped)

        val row = jobRow(jobId)
        assertEquals(TranscriptJobEntity.Status.Failed, row.status)
        assertEquals(TranscriptJobEntity.ErrorCode.Interrupted, row.errorCode)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
        assertEquals(0, subtitles().size)
    }

    @Test
    fun 文件已不在原位置时报解码失败且不启动推理() = runBlocking {
        val jobId = enqueueBook("file:///gone.mp3", fileStatus = AudioBookEntity.FileStatus.Missing)

        val outcome = coordinator().run(jobId)
        val failed = outcome as? TranscriptOutcome.Failed ?: error("文件都没了还能跑：$outcome")
        assertEquals(TranscriptFailure.DecodeFailed, failed.failure)
        assertTrue("错误里要点书名：${failed.message}", failed.message.contains("那些花儿"))
        assertEquals(TranscriptJobEntity.ErrorCode.DecodeFailed, jobRow(jobId).errorCode)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
        assertEquals(0, subtitles().size)
    }

    /**
     * SAF 那本书的授权判定。这条测试一开始是为了踩出一个真 bug：
     * 库里存的是**文档** uri（`.../document/primary%3AMusic%2F01.mp3`），可持久化授权发在**树** uri 上
     * （`.../tree/primary%3AMusic`），拿文档 uri 去比 `grantedTreeUris()` 永远得 false
     * ——真机上每一本正常授权的 SAF 书都会被误报「授权已失效」。所以现在查的是所属文件夹的树 uri。
     */
    @Test
    fun 授权查的是所属文件夹的树而不是文档uri() = runBlocking {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val docUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F01.mp3"
        db.importedFolderDao().upsert(
            ImportedFolderEntity(id = "folder-saf", treeUri = treeUri, displayName = "Music", grantedAtMs = 1L),
        )

        // 没授权：必须停在授权这一关，且原因说的是授权而不是「模型缺了」
        val jobId = enqueueBook(docUri, folderId = "folder-saf")
        val denied = coordinator(emptyModelsRoot()).run(jobId) as? TranscriptOutcome.Failed
            ?: error("没授权竟然能跑：$jobId")
        assertEquals(TranscriptFailure.PermissionRevoked, denied.failure)
        assertTrue("错误里要点出是哪个文件夹：${denied.message}", denied.message.contains(treeUri))
        assertEquals(TranscriptJobEntity.ErrorCode.PermissionRevoked, jobRow(jobId).errorCode)

        // 给了授权：过了授权关，下一个卡点是模型（用空模型目录，不烧推理）
        grants.granted += treeUri
        val retried = (repository.retry(jobId, ModelCatalog.transcriptRef) as EnqueueOutcome.Created).jobId
        val stillBlocked = coordinator(emptyModelsRoot()).run(retried) as? TranscriptOutcome.Failed
            ?: error("有授权、缺模型，应该报模型：$retried")
        assertEquals(TranscriptFailure.ModelMissing, stillBlocked.failure)
    }

    @Test
    fun 模型没准备好时标MODEL_MISSING且不写字幕() = runBlocking {
        // uri 只是占位：协调器在核对模型这一步就停了，不该碰文件
        val jobId = enqueueBook("file:///whatever.mp3")

        val failed = coordinator(emptyModelsRoot()).run(jobId) as? TranscriptOutcome.Failed
            ?: error("缺模型竟然跑通了")
        assertEquals(TranscriptFailure.ModelMissing, failed.failure)
        assertTrue("错误里要点名是哪个模型：${failed.message}", failed.message.contains(ModelCatalog.SILERO_VAD_ID))

        val row = jobRow(jobId)
        assertEquals(TranscriptJobEntity.Status.Failed, row.status)
        assertEquals(TranscriptJobEntity.ErrorCode.ModelMissing, row.errorCode)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
        assertEquals(0, subtitles().size)
    }

    /**
     * 静音素材必须**不能**报成功。这是 AGENTS.md §5 的正面检查：
     * 「跑完了」不等于「有字幕」，把 0 句标成 SUCCESS 等于告诉用户这本书已经转录好了。
     */
    @Test
    fun 没有语音的素材标成结果不可用而不是成功() = runBlocking {
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.SILENT_WAV))

        val failed = coordinator().run(jobId) as? TranscriptOutcome.Failed
            ?: error("静音素材竟然提交了字幕（这就是「造时间戳」会走到的地方）")
        assertEquals(TranscriptFailure.OutputUnreliable, failed.failure)
        assertTrue("要说清是模型没给字：${failed.message}", failed.message.contains("没有给出任何带时间的字"))

        val row = jobRow(jobId)
        assertEquals(TranscriptJobEntity.Status.Failed, row.status)
        assertEquals(TranscriptJobEntity.ErrorCode.OutputUnreliable, row.errorCode)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
        assertEquals("没字幕可写：库里的行必须与 FAILED 一致", 0, subtitles().size)
    }



    /**
     * 取消是「点取消的那一方」改状态（`repo.cancel` 把行标 CANCELLED），协调器只负责停手：
     * 既不写字幕，也不能给一个自己的终态——否则迟到的 worker 会把 CANCELLED 覆盖成 SUCCESS 或 FAILED。
     */
    @Test
    fun 取消位为真时立刻停手且不写终态() = runBlocking {
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.SILENT_WAV))

        val outcome = coordinator().run(jobId, isCancelled = { true })
        assertTrue("没走取消支路：$outcome", outcome is TranscriptOutcome.Cancelled)

        // 协调器只把它推到 RUNNING 就停了，终态留给取消的那一方写
        assertEquals(TranscriptJobEntity.Status.Running, jobRow(jobId).status)
        assertNull(jobRow(jobId).errorCode)
        assertEquals(0, subtitles().size)
    }

    /**
     * 「首次播放实时转录并显示」的协调器半边：识别进行中预览要**逐段增长**（不是憋到最后一次性给出），
     * 收尾（无论成败）预览清场、由库里那份正式字幕接管。
     *
     * 收集器必须放在**另一个真实线程**上：`recognizeAll` 全程阻塞测试线程（解码回调里逐段识别），
     * runBlocking 的事件循环在中途没有机会跑别的协程；StateFlow 的更新发生在回调线程上，
     * IO 线程上的收集者才能实时看到。中间值允许被 conflated，只要看得到增长与清场就足够。
     */
    @Test
    fun 识别过程中实时预览逐段增长收尾清场() = runBlocking {
        val durationMs = DeviceAsrFixtures.REAL_MP3_DURATION_MS
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_MP3), durationMs = durationMs)
        val store = com.xinchen.echomind.domain.transcript.LiveTranscriptStore()
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val collector = launch(Dispatchers.IO) {
            store.live.collect { map ->
                map[AUDIO_ID]?.let { live ->
                    if (snapshots.isEmpty() || snapshots.last() != live.sentences.size) {
                        snapshots += live.sentences.size
                    }
                }
            }
        }

        val outcome = try {
            TranscriptCoordinator(context, audioRepository, grants, repository, modelsRoot, SteppingClock(), liveStore = store)
                .run(jobId)
        } finally {
            collector.cancel()
        }

        val committed = outcome as? TranscriptOutcome.Committed
            ?: error("端到端没跑成（$outcome），预览的增长无从谈起")
        Log.i(TAG, "实时预览快照序列=$snapshots，收尾句数=${committed.sentences}")

        assertTrue("运行中预览一次都没出现：$snapshots", snapshots.isNotEmpty())
        assertTrue("预览里一句都没有：$snapshots", snapshots.last() > 0)
        assertEquals("预览只涨不缩", snapshots, snapshots.sorted())
        // 收尾清场：字幕此后以库里那份为准（本条用例里 in-memory Room 的行已在 committed.sentences 里）
        assertNull("run 返回后预览必须已清空", store.live.value[AUDIO_ID])
        assertEquals(committed.sentences.toLong(), subtitles().size.toLong())
    }

    /**
     * D44：`.wma` 不再直接解给 VAD，而是先转成 mp3 落到 `WmaPlaybackStore`，再走 MediaCodec。
     * 断言四件事：字幕真的进库了；mp3 产物在约定位置（播放也能直接用）；进度先报转码阶段再报识别阶段、
     * 分母是容器时长；转码出的时长回填给了书。第二轮同一本书复用产物（不再转码、不再回填）。
     */
    @Test
    fun wma先转mp3再识别且进度分两个阶段() = runBlocking {
        val durationMs = DeviceAsrFixtures.REAL_WMA_DURATION_MS
        val jobId = enqueueBook(fileUriOf(DeviceAsrFixtures.REAL_WMA), durationMs = 0, fileName = "a.wma")
        val store = WmaPlaybackStore(File(context.cacheDir, "coordinator-wma-${System.nanoTime()}"))
        val prepared = mutableListOf<Triple<String, String, Long>>()
        val reported = mutableListOf<TranscriptProgress>()
        val coordinator = coordinator(wmaStore = store, onWmaPrepared = { id, uri, ms -> prepared += Triple(id, uri, ms) })

        val outcome = coordinator.run(jobId) { progress -> reported += progress }

        val committed = outcome as? TranscriptOutcome.Committed
            ?: error("WMA 端到端没跑成（$outcome）：转码或识别有一环断了")
        Log.i(TAG, "WMA 端到端：${committed.segments} 段 → ${committed.sentences} 句，进度 ${reported.size} 次，回填 $prepared")
        assertTrue("一句都没有：$committed", committed.sentences > 0)
        assertEquals(TranscriptJobEntity.Status.Success, jobRow(jobId).status)
        assertTrue("mp3 产物不在约定位置", store.isReady(AUDIO_ID, "mp3"))
        assertTrue("part 临时文件没清", store.fileOf(AUDIO_ID, "mp3").parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })

        val phases = reported.map { it.phase }
        assertEquals("第一次上报该是转码阶段：$phases", TranscriptPhase.Transcoding, phases.first())
        assertTrue("识别阶段一次都没报：$phases", TranscriptPhase.Recognizing in phases)
        assertEquals("阶段只许从转码走向识别，不许回头", phases, phases.sortedBy { it.ordinal })
        reported.filter { it.phase == TranscriptPhase.Transcoding }.let { transcoding ->
            assertTrue("转码分母该是 WMA 容器时长：${transcoding.first()}", transcoding.all { Math.abs(it.durationMs - durationMs) < 1_000 })
            assertTrue("转码位置必须单调", transcoding.map { it.positionMs }.zipWithNext().all { (a, b) -> b >= a })
        }
        reported.filter { it.phase == TranscriptPhase.Recognizing }.let { recognizing ->
            assertTrue("识别分母该是 mp3 的时长：${recognizing.first()}", recognizing.all { Math.abs(it.durationMs - durationMs) < 1_500 })
        }
        assertEquals(listOf(AUDIO_ID), prepared.map { it.first })
        assertTrue("回填的时长不对：${prepared[0]}", Math.abs(prepared[0].third - durationMs) < 500)
        // 导入时 WMA 读不到时长，这里通过回填 lambda 写回（生产装配里接的是 setPreparedDuration）
        assertEquals(fileUriOf(DeviceAsrFixtures.REAL_WMA), prepared[0].second)

        // 第二轮（重试）：产物复用，不再转码、不再回填，进度直接从识别阶段开始
        val second = (repository.enqueue(AUDIO_ID, ModelCatalog.transcriptRef, force = true) as EnqueueOutcome.Created).jobId
        val reportedAgain = mutableListOf<TranscriptProgress>()
        val again = coordinator.run(second) { progress -> reportedAgain += progress }
        assertTrue("第二轮没跑成：$again", again is TranscriptOutcome.Committed)
        assertEquals("第二轮不该再转码", 1, prepared.size)
        assertTrue("第二轮不该有转码阶段：${reportedAgain.map { it.phase }}", reportedAgain.none { it.phase == TranscriptPhase.Transcoding })
    }

    /**
     * 「自动选模型」：探测在抢到车道（RUNNING）之后做（D36：SenseVoice 226 MB 不能与别的引擎同时在内存），
     * 所以 `updateModel` 必须对 RUNNING 行也有效——否则行上会一直挂着 `auto`，字幕是谁跑出来的就说不清了。
     * 中文素材 → Paraformer（`ModelRouting` 的汉字多数票）。
     */
    @Test
    fun 自动选模型在抢到车道后探测并把行上的模型改写为真正执行的那个() = runBlocking {
        val jobId = enqueueBook(
            fileUriOf(DeviceAsrFixtures.REAL_MP3),
            modelRef = TranscriptModelRef(ModelCatalog.AUTO_MODEL_ID, ""),
        )
        assertEquals(ModelCatalog.AUTO_MODEL_ID, jobRow(jobId).modelId)

        val outcome = coordinator().run(jobId)

        assertTrue("自动路由端到端没跑成：$outcome", outcome is TranscriptOutcome.Committed)
        val row = jobRow(jobId)
        assertEquals(TranscriptJobEntity.Status.Success, row.status)
        assertEquals(ModelCatalog.paraformer.id, row.modelId)
        assertEquals(ModelCatalog.refOf(ModelCatalog.paraformer).version, row.modelVersion)
        assertTrue(subtitles().isNotEmpty())
    }

    private fun emptyModelsRoot(): File =
        File(context.externalCacheDir ?: context.cacheDir, "no-models-for-coordinator").apply { mkdirs() }
}
