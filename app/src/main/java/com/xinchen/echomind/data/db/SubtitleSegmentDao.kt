package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 字幕读取。二分查找在内存里对整本有序列表做，所以这里只提供「按 `segment_index` 顺序整本取」。
 *
 * 替换语义（FR-605）：删旧 + 插新必须在**同一个事务**里，所以这两个方法都由
 * `SubtitleRepositoryImpl` 包进 `db.withTransaction`，DAO 自己不开事务。
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

    @Query("DELETE FROM subtitle_segment WHERE audio_id = :audioId")
    suspend fun deleteByAudio(audioId: String): Int

    @Insert
    suspend fun insertAll(segments: List<SubtitleSegmentEntity>)
}
