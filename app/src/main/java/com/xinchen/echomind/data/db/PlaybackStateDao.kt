package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureRow(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE audio_id = :audioId")
    fun observe(audioId: String): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state WHERE audio_id = :audioId")
    suspend fun get(audioId: String): PlaybackStateEntity?

    /**
     * 位置/时长/进度的落盘点。
     * `last_played_at_ms` 由调用方传入「这次收听活动的时刻」：开场与 flush 用现在，
     * 结算用会话的结束时刻——与 [recomputeAll] 从会话行推出的定义逐字一致，FR-410 才可能对平。
     * `duration_ms` 只在上报值有效（>0）时覆盖：Media3 在 prepare 之前会给出未知时长，
     * 已知的时长不该被一次「还没读到」抹成 0。
     */
    @Query(
        """
        UPDATE playback_state
        SET last_position_ms = :positionMs,
            duration_ms = CASE WHEN :durationMs > 0 THEN :durationMs ELSE duration_ms END,
            playback_speed = :speed, last_played_at_ms = :lastPlayedAtMs, updated_at_ms = :nowMs,
            is_finished = :finished
        WHERE audio_id = :audioId
        """,
    )
    suspend fun saveProgress(audioId: String, positionMs: Long, durationMs: Long, speed: Float, lastPlayedAtMs: Long, nowMs: Long, finished: Boolean)

    @Query("UPDATE playback_state SET playback_speed = :speed, updated_at_ms = :nowMs WHERE audio_id = :audioId")
    suspend fun saveSpeed(audioId: String, speed: Float, nowMs: Long)

    /**
     * 会话结算时的增量维护（FR-410 的「增量」路径）。
     * 与 [recomputeAll] 必须等价，仪器化测试专门对账这两条路。
     * 只管三个计数/时长缓存——`last_played_at_ms` 由 [saveProgress] 单点维护，
     * 两处都写就会出现「增量与重算各自更新一半」的漂移。
     * 书被从书架删除后这一句匹配 0 行——历史留在 listening_session，缓存随书消失（重新导入后可用重算重建）。
     */
    @Query(
        """
        UPDATE playback_state
        SET play_count = play_count + :playCountInc,
            completed_count = completed_count + :completedCountInc,
            listened_ms_total = listened_ms_total + :listenedMs,
            last_completed_at_ms = CASE WHEN :completedCountInc > 0 THEN :endedAtMs ELSE last_completed_at_ms END,
            updated_at_ms = :nowMs
        WHERE audio_id = :audioId
        """,
    )
    suspend fun applySettledSession(audioId: String, playCountInc: Int, completedCountInc: Int, listenedMs: Long, endedAtMs: Long, nowMs: Long)

    /**
     * 从会话表全量重算聚合缓存（FR-410/905，崩溃后的对账入口）。
     *
     * 计数与时长只算 `ended_at_ms > 0` 的行：正在进行的会话还没结算，增量路径也还没把它计入，
     * 否则「重算」永远比「增量」多一截，两条路就对不上账。
     * `last_played_at_ms` 例外——它记的是「最后一次收听活动发生的时刻」，进行中的会话本身就是最新活动，
     * 所以取 `ended_at_ms`（已结算）与 `updated_at_ms`（进行中，等于最后一次 flush 的时刻）两者的最大值。
     */
    @Query(
        """
        UPDATE playback_state
        SET play_count = (SELECT COUNT(*) FROM listening_session
                          WHERE listening_session.audio_id = playback_state.audio_id
                            AND ended_at_ms > 0 AND is_counted = 1),
            completed_count = (SELECT COUNT(*) FROM listening_session
                          WHERE listening_session.audio_id = playback_state.audio_id
                            AND ended_at_ms > 0 AND completed = 1),
            listened_ms_total = (SELECT COALESCE(SUM(listened_ms), 0) FROM listening_session
                          WHERE listening_session.audio_id = playback_state.audio_id AND ended_at_ms > 0),
            last_played_at_ms = COALESCE((SELECT MAX(CASE WHEN ended_at_ms > 0 THEN ended_at_ms ELSE updated_at_ms END)
                          FROM listening_session
                          WHERE listening_session.audio_id = playback_state.audio_id),
                          last_played_at_ms),
            last_completed_at_ms = COALESCE((SELECT MAX(ended_at_ms) FROM listening_session
                          WHERE listening_session.audio_id = playback_state.audio_id AND ended_at_ms > 0 AND completed = 1),
                          last_completed_at_ms)
        """,
    )
    suspend fun recomputeAll()

    @Query("SELECT COUNT(*) FROM playback_state")
    suspend fun rowCount(): Int

    @Query("DELETE FROM playback_state WHERE audio_id = :audioId")
    suspend fun delete(audioId: String)
}
