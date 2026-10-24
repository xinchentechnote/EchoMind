package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.model.TranscriptProgress
import kotlinx.coroutines.flow.Flow

/**
 * 转录任务的唯一入口（`docs/architecture.md` §4）。
 *
 * UI 与 worker 都不许直接碰 DAO；跑推理也不在这里（那是 `asr/TranscriptCoordinator` 的活）。
 *
 * 两条必须记住的事务边界（AGENTS.md §4 / `docs/database-design.md` §9）：
 * 1. 状态跃迁与 `audio_book.transcription_state` 一起提交，否则书架显示的和真实跑过的会对不上；
 * 2. 重转录的「删旧字幕 + 插新字幕 + 标 SUCCESS」在同一个事务里，中途崩溃不会留下半本新半本旧。
 *
 * **每本书只保留一行任务**（2026-10-24 起）：入队/重试会先删掉这本书已结束的历史行；
 * 用户也可以手动删掉某一行（[delete]）。
 */
interface TranscriptRepository {

    /** 全部任务，新建的在前（转录页列表读这一条） */
    fun jobs(): Flow<List<TranscriptJobRow>>

    /** 某本书当前在跑/排队的任务，没有则 null（书架那一格与「重试」按钮的可用性读它） */
    fun observeActiveJob(audioId: String): Flow<TranscriptJobRow?>

    /**
     * 入队（FR-506/507）。结局用返回值而不是异常表达，重复点按钮是正常业务：
     * 这本书已有活动任务 → [EnqueueOutcome.AlreadyActive]；已是 READY 且没 `force` → [EnqueueOutcome.AlreadyTranscribed]；
     * 书不在书架上 → [EnqueueOutcome.AudioNotFound]。
     * 建新行之前会在同一个事务里删掉这本书已结束的旧行（一本书一行）。
     */
    suspend fun enqueue(audioId: String, model: TranscriptModelRef, force: Boolean = false): EnqueueOutcome

    /** QUEUED/RUNNING → CANCELLED。返回 false = 那一行已经结束了，取消晚了 */
    suspend fun cancel(jobId: String): Boolean

    /**
     * 用户删一条记录：只删已结束的行（活动行先取消）。随行一起删掉**这一轮**产出的 ASR 字幕
     * （`subtitle_segment.job_id` 指向它的那些），书架状态按剩余字幕回退（无 ASR 字幕 → NONE）。
     * 返回 false = 行不存在或还在跑。
     */
    suspend fun delete(jobId: String): Boolean

    /**
     * 把 WorkManager 的请求 id 写回这一行（取消按钮拿它去 cancel）。
     * 返回 false = 行已经不活动了，此时**不该**再去 cancel 那个请求——id 没写进去，
     * 而任务已经有个别的结局，翻回来只会覆盖真实结果。
     */
    suspend fun attachWorkRequest(jobId: String, workRequestId: String): Boolean

    /** 按 id 取一行；worker 只拿到 jobId，需要它查出 audioId。行不存在（书被删、级联带走了）返回 null */
    suspend fun jobById(jobId: String): TranscriptJobRow?

    /**
     * 「自动选模型」在语言探测后把行上的 auto 改写为真正执行的模型。
     * 对 QUEUED/RUNNING 的行有效（探测发生在抢到车道之后，D36）；返回 false = 行已结束，不许改。
     */
    suspend fun updateModel(jobId: String, model: TranscriptModelRef): Boolean

    /**
     * 失败/取消的任务重开一轮 = 对同一本书再入队一次（`force=true`）。
     * 旧行在入队的事务里被删掉，新行取而代之——一本书只看得到最近一次的结果。
     * jobId 查不到时返回 [EnqueueOutcome.JobNotFound]。
     */
    suspend fun retry(jobId: String, model: TranscriptModelRef): EnqueueOutcome

    /**
     * QUEUED → RUNNING，[totalSegments] 由 VAD 跑完后的段数填入。
     *
     * 返回 `false` 有两种：这一行不是 QUEUED（被取消/已结算），**或者车道满了**——
     * 同时在跑的任务已达 `AudioFormats.ASR_MAX_CONCURRENT_JOBS`（多路共享一份引擎，D46），
     * 抢不到就等下一轮再来（调用方用 [runningCount] 与行状态区分这两种）。
     */
    suspend fun markRunning(jobId: String, totalSegments: Int): Boolean

    /** 正在跑（RUNNING）的任务数：worker 起前台通知前先看车道，协调器据此区分「车道满」与「行已结算」 */
    suspend fun runningCount(): Int

    /** 进度节流写库（≥1s，AGENTS.md §4）。只在 RUNNING 时有效 */
    suspend fun updateProgress(jobId: String, progress: TranscriptProgress): Boolean

    /**
     * 单事务收尾（FR-504/506/605）：删旧字幕 + 插新字幕（`source=ASR`、`job_id` 指向这一行）+
     * 任务 SUCCESS（`done_segments = total_segments = [totalSegments]`，VAD 跑完数出来的语音段数）+
     * `transcription_state=READY`。返回写入的句数。
     *
     * ⚠ **`segments` 必须已按 `startMs` 升序**（FR-601）：`segment_index` 就是传入顺序的重编号，
     * 这里既不排序也不检查顺序。排序会把「上游给了乱序」这个 bug 悄悄抹平，而库里
     * 「索引序 = 时间序」正是 `SubtitleSynchronizer` 二分查找的前提——所以这条由调用方保证，
     * 协调器那边用 `sortedBy` 把它写成契约（`SentenceSplitter` 的单调性有 JVM 性质断言兜着）。
     */
    suspend fun commitSubtitles(jobId: String, segments: List<SubtitleSegment>, totalSegments: Int): Int

    /** QUEUED/RUNNING → FAILED，同时把书架标 FAILED */
    suspend fun markFailed(jobId: String, failure: TranscriptFailure, message: String?): Boolean

    /**
     * 重启恢复（FR-507）：进程被杀时还挂在 QUEUED/RUNNING 的行标 `FAILED(INTERRUPTED)`，
     * 对应书籍一并标 FAILED。返回处理了几行。启动时跑一次，幂等。
     */
    suspend fun recoverInterrupted(): Int

    /**
     * 启动时把升级前积累的「同一本书多行」收口成一行（只留最新的那一行；活动行不动）。
     * 返回删了几行。幂等；正常运行期间入队自己会维持一本书一行，这一条只是给历史数据补课。
     */
    suspend fun collapseHistory(): Int
}
