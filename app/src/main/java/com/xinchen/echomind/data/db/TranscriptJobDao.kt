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
     * QUEUED → RUNNING。分段总数此时才由 VAD 的结果填进去。
     *
     * `NOT EXISTS (... status = 'RUNNING')` 是**全机同时只跑一个转录**的那道闸：模型句柄一份 243 MB，
     * 两本书同时开就是 OOM（AGENTS.md §3，`docs/architecture.md` D36）。守卫写在 SQL 里而不是「先查再改」，
     * 因为查与改之间正是并发窗口；0 行由仓储翻成 `false`，协调器据此在装载模型之前就收手。
     */
    @Query(
        "UPDATE transcript_job SET status = 'RUNNING', started_at_ms = :startedAtMs, total_segments = :totalSegments " +
            "WHERE id = :id AND status = 'QUEUED' " +
            "AND NOT EXISTS (SELECT 1 FROM transcript_job WHERE status = 'RUNNING' AND id <> :id)",
    )
    suspend fun markRunning(id: String, startedAtMs: Long, totalSegments: Int): Int

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

    /** 进度节流写库（≥1s，AGENTS.md §4），只在 RUNNING 时有效 */
    @Query("UPDATE transcript_job SET done_segments = :doneSegments, total_segments = :totalSegments WHERE id = :id AND status = 'RUNNING'")
    suspend fun updateProgress(id: String, doneSegments: Int, totalSegments: Int): Int

    /**
     * 推理前归一模型身份：当前把升级前遗留的 auto/多语种任务改为中文听书模型。
     * 只许改 QUEUED 的行——开始跑之后再换模型等于篡改历史。
     */
    @Query(
        "UPDATE transcript_job SET model_id = :modelId, model_version = :modelVersion " +
            "WHERE id = :id AND status = 'QUEUED'",
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
