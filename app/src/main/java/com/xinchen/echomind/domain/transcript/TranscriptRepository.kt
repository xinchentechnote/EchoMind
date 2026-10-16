package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptModelRef
import kotlinx.coroutines.flow.Flow

/**
 * 转录任务的唯一入口（`docs/architecture.md` §4）。
 *
 * UI 与 worker 都不许直接碰 DAO；跑推理也不在这里（那是 `asr/TranscriptCoordinator` 的活）。
 *
 * 两条必须记住的事务边界（AGENTS.md §4 / `docs/database-design.md` §9）：
 * 1. 状态跃迁与 `audio_book.transcription_state` 一起提交，否则书架显示的和真实跑过的会对不上；
 * 2. 重转录的「删旧字幕 + 插新字幕 + 标 SUCCESS」在同一个事务里，中途崩溃不会留下半本新半本旧。
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
     */
    suspend fun enqueue(audioId: String, model: TranscriptModelRef, force: Boolean = false): EnqueueOutcome

    /** QUEUED/RUNNING → CANCELLED。返回 false = 那一行已经结束了，取消晚了 */
    suspend fun cancel(jobId: String): Boolean

    /**
     * 把 WorkManager 的请求 id 写回这一行（取消按钮拿它去 cancel）。
     * 返回 false = 行已经不活动了，此时**不该**再去 cancel 那个请求——id 没写进去，
     * 而任务已经有个别的结局，翻回来只会覆盖真实结果。
     */
    suspend fun attachWorkRequest(jobId: String, workRequestId: String): Boolean

    /** 按 id 取一行；worker 只拿到 jobId，需要它查出 audioId。行不存在（书被删、级联带走了）返回 null */
    suspend fun jobById(jobId: String): TranscriptJobRow?

    /**
     * 自动路由的落笔：把行上的 model_id/version 从「auto」改写为语言判定后的最终模型。
     * 只对该行还是 QUEUED 时有效（返回 false = 已经在跑/已结束，不许改）。
     */
    suspend fun updateModel(jobId: String, model: TranscriptModelRef): Boolean

    /**
     * 失败/取消的任务重开一轮：新建一行（旧行留着当错误上下文，见 `docs/database-design.md` §6），
     * 而不是把旧行改回 QUEUED——历史一覆盖就再没人知道上次为什么失败。
     * jobId 查不到时返回 [EnqueueOutcome.JobNotFound]。
     */
    suspend fun retry(jobId: String, model: TranscriptModelRef): EnqueueOutcome

    /**
     * QUEUED → RUNNING，[totalSegments] 由 VAD 跑完后的段数填入。
     *
     * 返回 `false` 有两种：这一行不是 QUEUED（被取消/已结算），**或者别的任务正在跑**——
     * 一台机器同一时刻只许有一个模型句柄，抢不到就收手（`docs/architecture.md` D36）。
     */
    suspend fun markRunning(jobId: String, totalSegments: Int): Boolean

    /** 进度节流写库（≥1s，AGENTS.md §4）。只在 RUNNING 时有效 */
    suspend fun updateProgress(jobId: String, doneSegments: Int, totalSegments: Int): Boolean

    /**
     * 单事务收尾（FR-504/506/605）：删旧字幕 + 插新字幕（`source=ASR`、`job_id` 指向这一行）+
     * 任务 SUCCESS + `transcription_state=READY`。返回写入的句数。
     *
     * ⚠ **`segments` 必须已按 `startMs` 升序**（FR-601）：`segment_index` 就是传入顺序的重编号，
     * 这里既不排序也不检查顺序。排序会把「上游给了乱序」这个 bug 悄悄抹平，而库里
     * 「索引序 = 时间序」正是 `SubtitleSynchronizer` 二分查找的前提——所以这条由调用方保证，
     * 协调器那边用 `sortedBy` 把它写成契约（`SentenceSplitter` 的单调性有 JVM 性质断言兜着）。
     */
    suspend fun commitSubtitles(jobId: String, segments: List<SubtitleSegment>): Int

    /** QUEUED/RUNNING → FAILED，同时把书架标 FAILED */
    suspend fun markFailed(jobId: String, failure: TranscriptFailure, message: String?): Boolean

    /**
     * 重启恢复（FR-507）：进程被杀时还挂在 QUEUED/RUNNING 的行标 `FAILED(INTERRUPTED)`，
     * 对应书籍一并标 FAILED。返回处理了几行。启动时跑一次，幂等。
     */
    suspend fun recoverInterrupted(): Int
}
