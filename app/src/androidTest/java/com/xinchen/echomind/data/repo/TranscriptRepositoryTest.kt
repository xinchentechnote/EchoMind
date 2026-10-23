package com.xinchen.echomind.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.SubtitleSegmentEntity
import com.xinchen.echomind.data.db.TranscriptJobEntity
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.model.TranscriptPhase
import com.xinchen.echomind.domain.model.TranscriptProgress
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 转录任务状态机的仪器化测试（真 SQLite：事务回滚、状态守卫、外键都得真跑，JVM 单测给不了）。
 *
 * 三件事是这里真正要守的（AGENTS.md §4 + docs/database-design.md §9）：
 * 1. `transcript_job.status` 与 `audio_book.transcription_state` 永远一起变，中间没有能看见不一致的窗口；
 * 2. 重转录的字幕是整本替换——写完一半崩了不能留半本新半本旧；
 * 3. 重复结算（协程和 WorkManager 撞上同一行）第二次必须改不动。
 *
 * 时间全走 [FakeClock]，没有 `Thread.sleep`（AGENTS.md §6）。
 */
@RunWith(AndroidJUnit4::class)
class TranscriptRepositoryTest {

    private class FakeClock(var wall: Long = 1_700_000_000_000L) : TimeSource {
        override fun monotonicMs(): Long = wall
        override fun wallMs(): Long = wall
    }

    private val model = TranscriptModelRef(id = "paraformer-zh-2023-09-14-int8", version = "f36a0433bcf0")

    private lateinit var db: EchoMindDatabase
    private lateinit var clock: FakeClock
    private lateinit var repo: TranscriptRepository

    @Before
    fun setUp() = runBlocking {
        clock = FakeClock()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EchoMindDatabase::class.java,
        ).build()
        repo = TranscriptRepositoryImpl(db, clock)
        db.importedFolderDao().upsert(
            ImportedFolderEntity(id = "folder-1", treeUri = "tree://music", displayName = "Music", grantedAtMs = 1L),
        )
        upsertBook()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 书的默认状态可变：`fileLastModifiedMs` 与转录状态要按每个用例的场景摆 */
    private fun upsertBook(
        audioId: String = "a",
        lastModifiedMs: Long = 1L,
        transcriptionState: String = AudioBookEntity.TranscriptionState.None,
    ) = runBlocking {
        db.audioBookDao().upsert(
            AudioBookEntity(
                id = audioId,
                uri = "uri://$audioId",
                folderId = "folder-1",
                relativePath = "$audioId.mp3",
                fileName = "$audioId.mp3",
                title = "第一首",
                sizeBytes = 1_000,
                fileLastModifiedMs = lastModifiedMs,
                transcriptionState = transcriptionState,
                addedAtMs = 1L,
                lastScannedAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
    }

    private suspend fun bookState(): String =
        db.audioBookDao().getById("a")!!.transcriptionState

    private suspend fun job(id: String): TranscriptJobEntity = db.transcriptJobDao().getById(id)!!

    private fun segments(vararg texts: String) = texts.mapIndexed { index, text ->
        SubtitleSegment(index = index, startMs = index * 1_000L, endMs = index * 1_000L + 900, text = text)
    }

    private fun progress(positionMs: Long, durationMs: Long, done: Int, phase: TranscriptPhase = TranscriptPhase.Recognizing) =
        TranscriptProgress(phase = phase, positionMs = positionMs, durationMs = durationMs, doneSegments = done)

    /** 自动选模型的改写发生在抢到车道（RUNNING）之后，所以 RUNNING 也许改；已结束的行是历史，不许动 */
    @Test
    fun 归一模型在QUEUED与RUNNING时可改结束后不可改() = runBlocking {
        val created = repo.enqueue("a", model) as EnqueueOutcome.Created
        assertTrue(repo.updateModel(created.jobId, TranscriptModelRef("sense-voice-zh-en-ja-ko-yue-int8", "abc123")))
        assertEquals("sense-voice-zh-en-ja-ko-yue-int8", job(created.jobId).modelId)

        repo.markRunning(created.jobId, totalSegments = 1)
        assertTrue(repo.updateModel(created.jobId, TranscriptModelRef("paraformer-zh-2023-09-14-int8", "xyz789")))
        assertEquals("paraformer-zh-2023-09-14-int8", job(created.jobId).modelId)

        repo.markFailed(created.jobId, TranscriptFailure.DecodeFailed, "x")
        assertFalse(repo.updateModel(created.jobId, TranscriptModelRef("sense-voice-zh-en-ja-ko-yue-int8", "abc123")))
        assertEquals("paraformer-zh-2023-09-14-int8", job(created.jobId).modelId)
    }

    @Test
    fun 入队写出QUEUED行并把书架标成QUEUED() = runBlocking {
        val outcome = repo.enqueue("a", model)
        val jobId = (outcome as EnqueueOutcome.Created).jobId

        val row = job(jobId)
        assertEquals("a", row.audioId)
        assertEquals(model.id, row.modelId)
        assertEquals(model.version, row.modelVersion)
        assertEquals(TranscriptJobEntity.Status.Queued, row.status)
        assertEquals(0, row.totalSegments)
        assertNull(row.startedAtMs)
        assertEquals(clock.wall, row.createdAtMs)
        assertEquals(AudioBookEntity.TranscriptionState.Queued, bookState())
    }

    @Test
    fun 重复入队返回同一个活动任务而不是排两个队() = runBlocking {
        val first = repo.enqueue("a", model) as EnqueueOutcome.Created
        clock.wall += 1_000
        val second = repo.enqueue("a", model) as EnqueueOutcome.AlreadyActive

        assertEquals(first.jobId, second.jobId)
        assertEquals(1, db.transcriptJobDao().activeAudioIds().size)
    }

    /** 跑完的书不该被第二次点击白烧一遍 CPU（FR-506「不重复识别」） */
    @Test
    fun 已就绪的书再入队被拦住而force放行() = runBlocking {
        val created = repo.enqueue("a", model) as EnqueueOutcome.Created
        repo.markRunning(created.jobId, totalSegments = 2)
        repo.commitSubtitles(created.jobId, segments("甲", "乙"), totalSegments = 2)
        clock.wall += 1_000

        assertEquals(EnqueueOutcome.AlreadyTranscribed, repo.enqueue("a", model))
        assertTrue(repo.enqueue("a", model, force = true) is EnqueueOutcome.Created)
    }

    /** 书架状态是 READY 但库里只剩手工测试字幕：那没被转录过，不能拦（AGENTS.md §5） */
    @Test
    fun 只有测试字幕的书不算已转录() = runBlocking {
        upsertBook(transcriptionState = AudioBookEntity.TranscriptionState.Ready)
        SubtitleRepositoryImpl(db, clock)
            .replace("a", segments("测试字幕的一句"), SubtitleSource.MANUAL_TEST)

        assertTrue(repo.enqueue("a", model) is EnqueueOutcome.Created)
    }

    @Test
    fun 不在书架的书建不出任务() = runBlocking {
        assertEquals(EnqueueOutcome.AudioNotFound, repo.enqueue("没有这本书", model))
    }

    @Test
    fun 跑完一轮后任务SUCCESS字幕带job_id且书架READY() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        clock.wall += 1_000

        assertTrue(repo.markRunning(jobId, totalSegments = 6))
        assertEquals(TranscriptJobEntity.Status.Running, job(jobId).status)
        assertEquals(6, job(jobId).totalSegments)
        assertNotNull(job(jobId).startedAtMs)
        assertEquals(AudioBookEntity.TranscriptionState.Running, bookState())

        // 进度的分子分母是音频时间（已解位置 / 容器时长），段数只是顺带记
        assertTrue(repo.updateProgress(jobId, progress(positionMs = 20_000, durationMs = 60_000, done = 2)))
        val running = job(jobId)
        assertEquals(2, running.doneSegments)
        assertEquals(TranscriptJobEntity.Phase.Recognizing, running.phase)
        assertEquals(20_000L, running.positionMs)
        assertEquals(60_000L, running.durationMs)
        assertEquals(1.0 / 3.0, repo.observeActiveJob("a").first()!!.progress!!.toDouble(), 0.0001)

        clock.wall += 1_000
        assertEquals(3, repo.commitSubtitles(jobId, segments("甲", "乙", "丙"), totalSegments = 6))

        val row = job(jobId)
        assertEquals(TranscriptJobEntity.Status.Success, row.status)
        // 分母沿用 VAD 那轮的 6 段：3 句字幕不等于只跑了 3 段，改成句数就是假进度
        assertEquals(6, row.totalSegments)
        assertEquals(6, row.doneSegments)
        // 终态不再有「进度」：百分比只属于 RUNNING（UI 对成功行显示的是段数）
        assertNull(repo.jobs().first().first { it.id == jobId }.progress)
        assertEquals(clock.wall, row.finishedAtMs)
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())

        val written = db.subtitleSegmentDao().getByAudio("a")
        assertEquals(listOf(0, 1, 2), written.map { it.segmentIndex })
        assertTrue(written.all { it.jobId == jobId })
        assertTrue(written.all { it.source == SubtitleSegmentEntity.Source.ASR })
    }

    /** FR-605：重转录是整本替换。旧句一句都不能留，否则用户看到半本新半本旧拼在一起 */
    @Test
    fun 重转录整本替换旧字幕且一本书只剩一行() = runBlocking {
        val first = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(first, totalSegments = 1)
        repo.commitSubtitles(first, segments("旧一", "旧二", "旧三"), totalSegments = 1)

        val second = (repo.enqueue("a", model, force = true) as EnqueueOutcome.Created).jobId
        repo.markRunning(second, totalSegments = 1)
        repo.commitSubtitles(second, segments("新一"), totalSegments = 1)

        val written = db.subtitleSegmentDao().getByAudio("a")
        assertEquals(listOf("新一"), written.map { it.text })
        assertEquals(second, written[0].jobId)
        // 一本书一行：上一轮的记录在入队时让位，列表里只看得到最近一次
        assertNull(db.transcriptJobDao().getById(first))
        assertEquals(listOf(second), repo.jobs().first().filter { it.audioId == "a" }.map { it.id })
    }

    /** 0 句要么是没跑完要么是解码全失败，标 SUCCESS 就是谎报「已转录」 */
    @Test
    fun 空字幕提交被拒绝且不写任何行() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(jobId, totalSegments = 1)

        val thrown = runCatching { repo.commitSubtitles(jobId, emptyList(), totalSegments = 1) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertEquals(TranscriptJobEntity.Status.Running, job(jobId).status)
        assertEquals(AudioBookEntity.TranscriptionState.Running, bookState())
        assertEquals(0, db.subtitleSegmentDao().getByAudio("a").size)
    }

    /** 协程与 WorkManager 的重试撞上同一行：第二次结算必须改不动，也不能把已提交的字幕抹掉 */
    @Test
    fun 重复结算第二次整体失败并回滚字幕() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(jobId, totalSegments = 1)
        repo.commitSubtitles(jobId, segments("第一句"), totalSegments = 1)

        val thrown = runCatching { repo.commitSubtitles(jobId, segments("迟到的一句"), totalSegments = 1) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)

        val written = db.subtitleSegmentDao().getByAudio("a")
        assertEquals(listOf("第一句"), written.map { it.text })
        assertEquals(TranscriptJobEntity.Status.Success, job(jobId).status)
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())
    }

    @Test
    fun 非法跃迁返回false而不是把已结束的任务改回运行() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        assertTrue(repo.markFailed(jobId, TranscriptFailure.ModelMissing, "模型没准备好"))

        assertFalse(repo.markRunning(jobId, totalSegments = 3))
        assertFalse(repo.updateProgress(jobId, progress(positionMs = 1_000, durationMs = 3_000, done = 1)))
        assertFalse(repo.markFailed(jobId, TranscriptFailure.Unknown, "再失败一次"))
        assertFalse(repo.cancel("不存在的任务"))

        val row = job(jobId)
        assertEquals(TranscriptJobEntity.Status.Failed, row.status)
        assertEquals(TranscriptJobEntity.ErrorCode.ModelMissing, row.errorCode)
        assertEquals("模型没准备好", row.errorMessage)
        assertEquals(TranscriptFailure.ModelMissing, repo.jobs().first().first { it.id == jobId }.failure)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
    }

    /**
     * D36：一份 int8 模型 243 MB，两本书同时开就是 OOM 而不是慢。
     * 这道闸写在**一条 SQL 语句里**而不是「先查有没有 RUNNING 再改」——查与改之间正是并发窗口，
     * 两次并发的「查」都能看见空车道，然后两个都改成功。
     */
    @Test
    fun 别的任务正在跑时第二本书的任务抢不到运行中的行() = runBlocking {
        upsertBook(audioId = "b")
        val first = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        val second = (repo.enqueue("b", model) as EnqueueOutcome.Created).jobId

        assertTrue(repo.markRunning(first, totalSegments = 4))
        assertFalse("第二本书竟然也拿到了运行中的行", repo.markRunning(second, totalSegments = 4))

        val blocked = job(second)
        assertEquals(TranscriptJobEntity.Status.Queued, blocked.status)
        assertNull("抢不到车道就不该有开始时间：$blocked", blocked.startedAtMs)
        // 入队本来就会把那本书标成 QUEUED（第一个用例守的就是这件事），关键是它没被翻成 RUNNING
        assertEquals(AudioBookEntity.TranscriptionState.Queued, db.audioBookDao().getById("b")!!.transcriptionState)
        // 同一行自己撞第二次还是老理由（不是 QUEUED），不是被这条新闸挡的
        assertFalse(repo.markRunning(first, totalSegments = 4))

        // 第一轮结算完让出车道，第二轮才抢得到
        repo.commitSubtitles(first, segments("第一轮的"), totalSegments = 4)
        assertTrue(repo.markRunning(second, totalSegments = 4))
    }

    /** 一本书一行：重试是新行**顶掉**旧行，用户看到的永远是这本书最近一次的结果 */
    @Test
    fun 重试用新行顶掉失败的旧行() = runBlocking {
        val failed = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markFailed(failed, TranscriptFailure.DecodeFailed, "解码失败")

        val retried = repo.retry(failed, model) as EnqueueOutcome.Created
        assertTrue(retried.jobId != failed)
        assertNull("旧行该在入队的事务里让位", db.transcriptJobDao().getById(failed))
        assertEquals(TranscriptJobEntity.Status.Queued, job(retried.jobId).status)
        assertEquals(1, repo.jobs().first().count { it.audioId == "a" })

        assertEquals(EnqueueOutcome.JobNotFound, repo.retry("没有这个任务", model))
        // 旧行已经没了，再拿它的 id 重试同样是 JobNotFound——界面上也不会再有这一行
        assertEquals(EnqueueOutcome.JobNotFound, repo.retry(failed, model))
    }

    /** 活动行不让位：正在跑的任务不会被同一本书的再次入队删掉（那是 AlreadyActive 的事） */
    @Test
    fun 入队只清这本书已结束的行不碰别的书() = runBlocking {
        upsertBook(audioId = "b")
        val aOld = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markFailed(aOld, TranscriptFailure.Unknown, "x")
        val bOld = (repo.enqueue("b", model) as EnqueueOutcome.Created).jobId
        repo.markFailed(bOld, TranscriptFailure.Unknown, "y")

        val aNew = (repo.enqueue("a", model, force = true) as EnqueueOutcome.Created).jobId

        assertNull(db.transcriptJobDao().getById(aOld))
        assertNotNull("别的书的历史行不该被 a 的入队波及", db.transcriptJobDao().getById(bOld))
        assertEquals(EnqueueOutcome.AlreadyActive(aNew), repo.enqueue("a", model, force = true))
        assertNotNull(db.transcriptJobDao().getById(aNew))
    }

    /** 取消没跑完的任务不该把书架留在 RUNNING：按库里到底有没有 ASR 字幕回退 */
    @Test
    fun 取消后书架回到NONE() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(jobId, totalSegments = 4)

        assertTrue(repo.cancel(jobId))
        assertEquals(TranscriptJobEntity.Status.Cancelled, job(jobId).status)
        assertEquals(TranscriptJobEntity.ErrorCode.Cancelled, job(jobId).errorCode)
        assertEquals(AudioBookEntity.TranscriptionState.None, bookState())
        assertNull(repo.observeActiveJob("a").first())

        assertFalse(repo.cancel(jobId))
    }

    @Test
    fun 取消重转录保住上一轮的好字幕() = runBlocking {
        val first = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(first, totalSegments = 1)
        repo.commitSubtitles(first, segments("上一轮跑好的"), totalSegments = 1)

        val second = (repo.enqueue("a", model, force = true) as EnqueueOutcome.Created).jobId
        repo.markRunning(second, totalSegments = 1)
        clock.wall += 1_000
        assertTrue(repo.cancel(second))

        assertEquals(listOf("上一轮跑好的"), db.subtitleSegmentDao().getByAudio("a").map { it.text })
        // 上一轮的行已让位（一本书一行），字幕的 job_id 由外键置空，但字幕本身一句不少
        assertNull(db.subtitleSegmentDao().getByAudio("a")[0].jobId)
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())
    }

    /**
     * FR-107：文件变了以后旧字幕不再可信。这时取消重转录必须回到 EXPIRED，
     * 谎报 READY 等于告诉用户「这副字幕跟当前文件对得上」。
     */
    @Test
    fun 文件变过以后取消重转录回到EXPIRED() = runBlocking {
        val first = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(first, totalSegments = 1)
        repo.commitSubtitles(first, segments("旧文件跑出来的"), totalSegments = 1)
        val subtitleMs = job(first).finishedAtMs

        // 扫描发现文件变了：mtime 比字幕新（这里直接摆 mtime，扫描路径自身有 AudioRepositoryImportTest 覆盖）
        upsertBook(lastModifiedMs = subtitleMs!! + 60_000, transcriptionState = AudioBookEntity.TranscriptionState.Expired)

        val second = (repo.enqueue("a", model, force = true) as EnqueueOutcome.Created).jobId
        repo.markRunning(second, totalSegments = 1)
        assertTrue(repo.cancel(second))

        assertEquals(AudioBookEntity.TranscriptionState.Expired, bookState())
        assertEquals(listOf("旧文件跑出来的"), db.subtitleSegmentDao().getByAudio("a").map { it.text })
    }

    /** FR-507：进程被杀时挂着的行标成 INTERRUPTED，重启后不能有个永远「正在转录」的鬼任务 */
    @Test
    fun 重启恢复把挂着的任务标成中断() = runBlocking {
        val running = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(running, totalSegments = 3)
        db.audioBookDao().upsert(
            db.audioBookDao().getById("a")!!.copy(id = "b", uri = "uri://b", relativePath = "b.mp3", fileName = "b.mp3"),
        )
        val queued = (repo.enqueue("b", model) as EnqueueOutcome.Created).jobId

        assertEquals(2, repo.recoverInterrupted())

        assertEquals(TranscriptJobEntity.Status.Failed, job(running).status)
        assertEquals(TranscriptJobEntity.ErrorCode.Interrupted, job(running).errorCode)
        assertEquals(TranscriptJobEntity.Status.Failed, job(queued).status)
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())
        assertEquals(AudioBookEntity.TranscriptionState.Failed, db.audioBookDao().getById("b")!!.transcriptionState)
        // 幂等：再跑一次没有东西可标
        assertEquals(0, repo.recoverInterrupted())
    }

    /** 进度分母还没出来时不能画 0%：那是假进度 */
    @Test
    fun 分段数未知时没有百分比() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId

        val row = repo.observeActiveJob("a").first()
        assertEquals(jobId, row!!.id)
        assertEquals(TranscriptJobStatus.Queued, row.status)
        assertNull(row.progress)
        assertTrue(row.status.isActive)
    }

    /**
     * worker 只拿到 jobId，得先查回这一行才能找到书；WorkManager 的请求 id 也要写回同一行，
     * 取消按钮才有东西可 cancel（`TranscriptWorkScheduler`）。
     */
    @Test
    fun 按id取行并把工作请求id写回活动任务() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId

        val loaded = repo.jobById(jobId)
        assertEquals(jobId, loaded!!.id)
        assertEquals("a", loaded.audioId)
        assertNull(loaded.workRequestId)

        assertTrue(repo.attachWorkRequest(jobId, "3f6c2a1e-0000-4000-8000-111122223333"))
        assertEquals("3f6c2a1e-0000-4000-8000-111122223333", repo.jobById(jobId)!!.workRequestId)

        assertNull(repo.jobById("没有这个任务"))
    }

    /** 行已经结束了就别再写请求 id：写了也没人读，返回 false 让调度方去收掉那个请求 */
    @Test
    fun 已结束的任务写不回工作请求id() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markFailed(jobId, TranscriptFailure.ModelMissing, "模型没准备好")

        assertFalse(repo.attachWorkRequest(jobId, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertNull(repo.jobById(jobId)!!.workRequestId)
    }

    // -- 删记录 / 启动收口（2026-10-24，一本书一行）-----------------------------------------

    /** 删成功记录 = 这一轮产出的字幕一起走，书架按「没有 ASR 字幕」回到 NONE；手工测试字幕不受影响 */
    @Test
    fun 删成功记录连同它的字幕并把书架回到NONE() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(jobId, totalSegments = 2)
        repo.commitSubtitles(jobId, segments("甲", "乙"), totalSegments = 2)
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())

        assertTrue(repo.delete(jobId))

        assertNull(db.transcriptJobDao().getById(jobId))
        assertEquals(0, db.subtitleSegmentDao().getByAudio("a").size)
        assertEquals(AudioBookEntity.TranscriptionState.None, bookState())
        assertTrue(repo.jobs().first().none { it.audioId == "a" })
        // 再删一次：行已不在，如实返回 false
        assertFalse(repo.delete(jobId))
    }

    /** 删失败记录不碰这本书留下来的（上一轮的）字幕：它们不是这一行产出的 */
    @Test
    fun 删失败记录不碰别的轮次留下的字幕() = runBlocking {
        val good = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        repo.markRunning(good, totalSegments = 1)
        repo.commitSubtitles(good, segments("好字幕"), totalSegments = 1)
        val bad = (repo.enqueue("a", model, force = true) as EnqueueOutcome.Created).jobId
        repo.markFailed(bad, TranscriptFailure.DecodeFailed, "x")
        assertEquals(AudioBookEntity.TranscriptionState.Failed, bookState())

        assertTrue(repo.delete(bad))

        assertEquals(listOf("好字幕"), db.subtitleSegmentDao().getByAudio("a").map { it.text })
        // 没有活动行、字幕还在且不比文件旧 → 书架按真实情况回到 READY，而不是停在 FAILED
        assertEquals(AudioBookEntity.TranscriptionState.Ready, bookState())
    }

    /** 活动行拒删：它正被 worker 写着，要先取消 */
    @Test
    fun 正在跑的行拒绝删除() = runBlocking {
        val jobId = (repo.enqueue("a", model) as EnqueueOutcome.Created).jobId
        assertFalse(repo.delete(jobId))
        repo.markRunning(jobId, totalSegments = 1)
        assertFalse(repo.delete(jobId))
        assertNotNull(db.transcriptJobDao().getById(jobId))
        assertEquals(AudioBookEntity.TranscriptionState.Running, bookState())
    }

    /** 升级前积累的多行在启动时收口：每本书只留最新一行，活动行不动，别的书不受影响 */
    @Test
    fun 启动收口只留每本书最新的一行且不碰活动行() = runBlocking {
        upsertBook(audioId = "b")
        // 直接摆历史行：入队本身现在已经不会制造重复了
        suspend fun put(id: String, audioId: String, status: String, createdAt: Long) = db.transcriptJobDao().insert(
            TranscriptJobEntity(id = id, audioId = audioId, modelId = model.id, modelVersion = model.version, status = status, createdAtMs = createdAt),
        )
        put("a-1", "a", TranscriptJobEntity.Status.Failed, 1_000)
        put("a-2", "a", TranscriptJobEntity.Status.Failed, 2_000)
        put("a-3", "a", TranscriptJobEntity.Status.Success, 3_000)
        put("b-1", "b", TranscriptJobEntity.Status.Failed, 1_000)
        put("b-2", "b", TranscriptJobEntity.Status.Running, 500) // 更旧但在跑：不能删

        assertEquals(2, repo.collapseHistory())

        assertEquals(setOf("a-3", "b-1", "b-2"), repo.jobs().first().map { it.id }.toSet())
        // 幂等
        assertEquals(0, repo.collapseHistory())
    }
}
