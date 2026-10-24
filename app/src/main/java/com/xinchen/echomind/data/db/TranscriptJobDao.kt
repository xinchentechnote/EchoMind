package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 转录任务的读写。
 *
 * 每条状态跃迁都带 `WHERE status IN (...)` 守卫并返回受影响行数，所以：
 * - 重复结算（协程与 WorkManager 的重试撞上同一行）第二次是 0 行，不会把 SUCCESS 改回 RUNNING；
 * - 非法跃迁（已 CANCELLED 的任务被 worker 写回 RUNNING）在 SQL 层面就失败，调用方看得见。
 *
 * 事务由 `TranscriptRepositoryImpl` 包（状态跃迁 + `audio_book.transcription_state` 必须一起提交），DAO 自己不开事务。
 */
@Dao
interface TranscriptJobDao {

    @Query("SELECT * FROM transcript_job ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<TranscriptJobEntity>>

    @Query("SELECT * FROM transcript_job WHERE audio_id = :audioId AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at_ms DESC LIMIT 1")
    fun observeActiveOf(audioId: String): Flow<TranscriptJobEntity?>

    @Query("SELECT * FROM transcript_job WHERE audio_id = :audioId AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at_ms DESC LIMIT 1")
    suspend fun activeOf(audioId: String): TranscriptJobEntity?

    @Query("SELECT audio_id FROM transcript_job WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeAudioIds(): List<String>

    @Query("SELECT * FROM transcript_job WHERE id = :id")
    suspend fun getById(id: String): TranscriptJobEntity?

    @Insert
    suspend fun insert(job: TranscriptJobEntity)

    /**
     * 「每本书只保留一行」（用户口径，2026-10-24）：入队前清掉这本书**已结束**的历史行。
     * 活动行不在清理范围——它由 `activeOf` 的「不重复排队」守卫处理。返回删了几行。
     */
    @Query("DELETE FROM transcript_job WHERE audio_id = :audioId AND status NOT IN ('QUEUED', 'RUNNING')")
    suspend fun deleteSettledOf(audioId: String): Int

    /**
     * 启动时的一次性收口：同一本书有多行时，只留最新的那一行（升级前的历史行与中断恢复留下的）。
     * 只删已结束的行；活动行即便更旧也不动——它可能正被 worker 写着。幂等。
     */
    @Query(
        "DELETE FROM transcript_job WHERE status NOT IN ('QUEUED', 'RUNNING') AND EXISTS (" +
            "SELECT 1 FROM transcript_job newer WHERE newer.audio_id = transcript_job.audio_id " +
            "AND (newer.created_at_ms > transcript_job.created_at_ms " +
            "OR (newer.created_at_ms = transcript_job.created_at_ms AND newer.id > transcript_job.id)))",
    )
    suspend fun deleteOutdatedDuplicates(): Int

    /** 用户删记录：只许删已结束的行（活动行先取消）。返回 0 = 行不存在或还在跑 */
    @Query("DELETE FROM transcript_job WHERE id = :id AND status NOT IN ('QUEUED', 'RUNNING')")
    suspend fun deleteSettled(id: String): Int

    /**
     * QUEUED → RUNNING。分段总数此时才由 VAD 的结果填进去。
     *
     * `(SELECT COUNT(*) … RUNNING AND id <> :id) < :maxConcurrent` 是**全机同时最多跑 N 个转录**的那道闸
     * （N = `AudioFormats.ASR_MAX_CONCURRENT_JOBS`，多路共享同一份引擎，D46）。守卫写在 SQL 里而不是「先查再改」，
     * 因为查与改之间正是并发窗口——N 个 worker 同时起跑时，只有前 N 个能把这条 UPDATE 改成 1 行；
     * 0 行由仓储翻成 `false`，协调器据此在装载模型之前就收手（车道满 = 稍后重试，行已结算 = 跳过）。
     */
    @Query(
        "UPDATE transcript_job SET status = 'RUNNING', started_at_ms = :startedAtMs, total_segments = :totalSegments " +
            "WHERE id = :id AND status = 'QUEUED' " +
            "AND (SELECT COUNT(*) FROM transcript_job WHERE status = 'RUNNING' AND id <> :id) < :maxConcurrent",
    )
    suspend fun markRunning(id: String, startedAtMs: Long, totalSegments: Int, maxConcurrent: Int): Int

    /** 正在跑的行数（worker 起前台通知前先看一眼车道，免得通知闪一下又因为车道满退出） */
    @Query("SELECT COUNT(*) FROM transcript_job WHERE status = 'RUNNING'")
    suspend fun runningCount(): Int

    /** RUNNING → SUCCESS。done 一并写终值，免得最后一块的进度没来得及节流写库 */
    @Query(
        "UPDATE transcript_job SET status = 'SUCCESS', finished_at_ms = :finishedAtMs, " +
            "done_segments = :doneSegments, total_segments = :totalSegments " +
            "WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun markSuccess(id: String, finishedAtMs: Long, doneSegments: Int, totalSegments: Int): Int

    @Query(
        "UPDATE transcript_job SET status = 'FAILED', finished_at_ms = :finishedAtMs, " +
            "error_code = :errorCode, error_message = :errorMessage " +
            "WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markFailed(id: String, finishedAtMs: Long, errorCode: String, errorMessage: String?): Int

    @Query(
        "UPDATE transcript_job SET status = 'CANCELLED', finished_at_ms = :finishedAtMs, " +
            "error_code = 'CANCELLED', error_message = :errorMessage " +
            "WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markCancelled(id: String, finishedAtMs: Long, errorMessage: String?): Int

    /**
     * 进度节流写库（≥1s，AGENTS.md §4），只在 RUNNING 时有效。
     * 分子分母是音频时间（`position_ms`/`duration_ms`），`done_segments` 顺带更新；`total_segments` 在收尾时才由 markSuccess 写。
     */
    @Query(
        "UPDATE transcript_job SET done_segments = :doneSegments, phase = :phase, position_ms = :positionMs, " +
            "duration_ms = :durationMs WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun updateProgress(id: String, doneSegments: Int, phase: String, positionMs: Long, durationMs: Long): Int

    /**
     * 推理前归一模型身份：「自动选模型」的任务在语言探测后把 auto 改写为真正执行的模型。
     * 探测在 markRunning **之后**做（引擎句柄要先抢到车道，D36），所以 RUNNING 的行也许改；
     * 只有写它的协调器本人会调，已结束的行仍然不许改——那是篡改历史。
     */
    @Query(
        "UPDATE transcript_job SET model_id = :modelId, model_version = :modelVersion " +
            "WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun updateModel(id: String, modelId: String, modelVersion: String): Int

    /**
     * 写回 WorkManager 的请求 id（取消要用）。同样只许改还没结束的行：
     * 一行已经 SUCCESS/FAILED 之后再挂个 work id，取消按钮就会去 cancel 一个不相干的请求。
     */
    @Query("UPDATE transcript_job SET work_request_id = :workRequestId WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun setWorkRequestId(id: String, workRequestId: String): Int

    /** 还挂在 QUEUED/RUNNING 的行全部标 FAILED(INTERRUPTED)，返回处理了几行（FR-507 重启恢复） */
    @Query(
        "UPDATE transcript_job SET status = 'FAILED', finished_at_ms = :finishedAtMs, " +
            "error_code = 'INTERRUPTED', error_message = '任务被中断（进程退出或系统重启）' " +
            "WHERE status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markInterrupted(finishedAtMs: Long): Int
}
