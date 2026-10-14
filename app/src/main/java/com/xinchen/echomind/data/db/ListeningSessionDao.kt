package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 统计页「最近收听」一行 */
data class RecentSessionRow(
    val id: Long,
    val audioId: String,
    val title: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

@Dao
interface ListeningSessionDao {

    @Insert
    suspend fun insert(entity: ListeningSessionEntity): Long

    /**
     * 进度 flush：按「该首当前 ACTIVE 的行」定位，**覆盖写**累计值而不是累加。
     * 这样崩溃后恢复不需要知道上一次 flush 到哪，重复 flush 也不会把同一段时间算两遍（FR-405）。
     * 返回受影响行数：0 说明没有 ACTIVE 行（调用方补插一条）。
     */
    @Query(
        """
        UPDATE listening_session
        SET listened_ms = :listenedMs, consumed_audio_ms = :consumedAudioMs,
            end_position_ms = :endPositionMs, max_reached_position_ms = :maxReachedPositionMs,
            duration_ms_snapshot = CASE WHEN :durationMs > 0 THEN :durationMs ELSE duration_ms_snapshot END,
            updated_at_ms = :nowMs
        WHERE audio_id = :audioId AND ended_at_ms = 0
        """,
    )
    suspend fun flushOpen(audioId: String, listenedMs: Long, consumedAudioMs: Long, endPositionMs: Long, maxReachedPositionMs: Long, durationMs: Long, nowMs: Long): Int

    /** 结算当前 ACTIVE 行：写最终数值 + ended_at + 状态/计数（ended_at_ms 一旦非 0 就退出 ACTIVE） */
    @Query(
        """
        UPDATE listening_session
        SET listened_ms = :listenedMs, consumed_audio_ms = :consumedAudioMs,
            end_position_ms = :endPositionMs, max_reached_position_ms = :maxReachedPositionMs,
            duration_ms_snapshot = :durationMsSnapshot, audio_title_snapshot = :titleSnapshot,
            ended_at_ms = :endedAtMs, completed = :completed, is_counted = :isCounted,
            session_status = :status, updated_at_ms = :nowMs
        WHERE audio_id = :audioId AND ended_at_ms = 0
        """,
    )
    suspend fun settleOpen(
        audioId: String,
        listenedMs: Long,
        consumedAudioMs: Long,
        endPositionMs: Long,
        maxReachedPositionMs: Long,
        durationMsSnapshot: Long,
        titleSnapshot: String,
        endedAtMs: Long,
        completed: Boolean,
        isCounted: Boolean,
        status: String,
        nowMs: Long,
    ): Int

    @Query("SELECT * FROM listening_session WHERE ended_at_ms = 0")
    suspend fun openSessions(): List<ListeningSessionEntity>

    @Query("SELECT COUNT(*) FROM listening_session WHERE audio_id = :audioId AND ended_at_ms = 0")
    suspend fun countOpen(audioId: String): Int

    @Query("SELECT * FROM listening_session WHERE id = :id")
    suspend fun byId(id: Long): ListeningSessionEntity?

    /** 某首已结算的会话（测试与「单首历史」用） */
    @Query(
        """
        SELECT * FROM listening_session
        WHERE audio_id = :audioId AND ended_at_ms > 0
        ORDER BY started_at_ms ASC
        """,
    )
    suspend fun settledOf(audioId: String): List<ListeningSessionEntity>

    /**
     * 崩溃恢复（FR-405）：把残留的 ACTIVE 行按最后一次 flush 的数值结算为 CRASHED。
     * ended_at 用 flush 时间（`updated_at_ms`）而不是「现在」——最后一次 flush 到进程死亡之间那段
     * 无法可信度量，宁可少算，也不把死掉的时间算成收听。
     * 阈值由参数传入，保持 `PlaybackPolicy` 是唯一定义处。
     * 状态字面量与 [ListeningSessionEntity.Status.CRASHED] 一致。
     */
    @Query(
        """
        UPDATE listening_session
        SET ended_at_ms = CASE WHEN updated_at_ms > 0 THEN updated_at_ms ELSE started_at_ms END,
            is_counted = CASE WHEN listened_ms >= :minListenedMs THEN 1 ELSE 0 END,
            session_status = 'CRASHED',
            updated_at_ms = CASE WHEN updated_at_ms > 0 THEN updated_at_ms ELSE started_at_ms END
        WHERE ended_at_ms = 0
        """,
    )
    suspend fun settleOpenAsCrashed(minListenedMs: Long): Int

    // ---- 统计页（只算已结算的行，进行中的会话等它结算再进统计，FR-411）----

    @Query("SELECT COALESCE(SUM(listened_ms), 0) FROM listening_session WHERE ended_at_ms > 0")
    fun observeTotalListenedMs(): Flow<Long>

    @Query("SELECT COUNT(*) FROM listening_session WHERE ended_at_ms > 0 AND is_counted = 1")
    fun observeTotalPlayCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM listening_session WHERE ended_at_ms > 0 AND completed = 1")
    fun observeTotalCompletedCount(): Flow<Int>

    @Query(
        """
        SELECT id AS id, audio_id AS audioId, audio_title_snapshot AS title,
               started_at_ms AS startedAtMs, listened_ms AS listenedMs, completed AS completed
        FROM listening_session WHERE ended_at_ms > 0
        ORDER BY started_at_ms DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<RecentSessionRow>>
}
