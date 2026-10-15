package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 字幕读取。二分查找在内存里对整本有序列表做，所以这里只提供「按 `segment_index` 顺序整本取」。
 *
 * 替换语义（FR-605）：删旧 + 插新必须在**同一个事务**里，所以这两个方法都由调用方
 * （`SubtitleRepositoryImpl`、`TranscriptRepositoryImpl`）包进 `db.withTransaction`，DAO 自己不开事务。
 */
@Dao
interface SubtitleSegmentDao {

    @Query("SELECT * FROM subtitle_segment WHERE audio_id = :audioId ORDER BY segment_index ASC")
    fun observeByAudio(audioId: String): Flow<List<SubtitleSegmentEntity>>

    @Query("SELECT * FROM subtitle_segment WHERE audio_id = :audioId ORDER BY segment_index ASC")
    suspend fun getByAudio(audioId: String): List<SubtitleSegmentEntity>

    @Query("SELECT COUNT(*) FROM subtitle_segment WHERE audio_id = :audioId")
    fun observeCount(audioId: String): Flow<Int>

    /** 这批字幕是哪来的（ASR / 手工测试）。整本替换在单个事务里完成，所以正常情况下只有一个来源 */
    @Query("SELECT source FROM subtitle_segment WHERE audio_id = :audioId GROUP BY source")
    fun observeSources(audioId: String): Flow<List<String>>

    /**
     * ASR 字幕的句数。手工测试字幕**不**计入：它证明不了这本书被转录过（AGENTS.md §5），
     * 「要不要拦掉重复转录」「取消后书架显示什么」都只看真实的 ASR 产物。
     */
    @Query("SELECT COUNT(*) FROM subtitle_segment WHERE audio_id = :audioId AND source = 'ASR'")
    suspend fun countAsrOf(audioId: String): Int

    /** 最近一批 ASR 字幕的落库时间，用来和 `audio_book.file_last_modified_ms` 比新旧（FR-107） */
    @Query("SELECT MAX(created_at_ms) FROM subtitle_segment WHERE audio_id = :audioId AND source = 'ASR'")
    suspend fun latestAsrCreatedAtMs(audioId: String): Long?

    @Query("DELETE FROM subtitle_segment WHERE audio_id = :audioId")
    suspend fun deleteByAudio(audioId: String): Int

    @Insert
    suspend fun insertAll(segments: List<SubtitleSegmentEntity>)
}
