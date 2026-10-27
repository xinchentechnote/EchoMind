package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AudioBookDao {

    /**
     * 用 @Upsert 而不是 @Insert(REPLACE)：REPLACE 在 SQLite 里是 DELETE + INSERT，
     * 会触发 playback_state 的 ON DELETE CASCADE，把重扫当成「删书」清掉播放进度与统计缓存。
     */
    @Upsert
    suspend fun upsert(book: AudioBookEntity)

    @Upsert
    suspend fun upsertAll(books: List<AudioBookEntity>)

    @Query("SELECT id FROM audio_book WHERE id IN (:ids)")
    suspend fun presentIds(ids: List<String>): List<String>

    @Query("SELECT * FROM audio_book WHERE id = :audioId")
    suspend fun getById(audioId: String): AudioBookEntity?

    @Query("SELECT COUNT(*) FROM audio_book")
    suspend fun count(): Int

    /** 全库 uri（本机音频页的「已在书架」角标用）；量级几千行，一次性读入 */
    @Query("SELECT uri FROM audio_book")
    suspend fun allUris(): List<String>

    @Query("SELECT COUNT(*) FROM audio_book WHERE folder_id = :folderId")
    suspend fun countOfFolder(folderId: String): Int

    /** 去重判定只需要这几列，别把整行读进内存 */
    @Query("SELECT id, uri, size_bytes AS sizeBytes, file_last_modified_ms AS lastModifiedMs, title_is_manual AS titleIsManual, file_status = 'MISSING' AS fileMissing FROM audio_book WHERE folder_id = :folderId")
    suspend fun existingForFolder(folderId: String): List<ExistingAudioRow>

    @Query("UPDATE audio_book SET file_status = :status, updated_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun setFileStatus(ids: List<String>, status: String, nowMs: Long)

    @Query("UPDATE audio_book SET transcription_state = :state, updated_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun setTranscriptionState(ids: List<String>, state: String, nowMs: Long)

    @Query("UPDATE audio_book SET title = :title, title_is_manual = 1, updated_at_ms = :nowMs WHERE id = :audioId")
    suspend fun rename(audioId: String, title: String, nowMs: Long)

    @Query("UPDATE audio_book SET is_favorite = :favorite, updated_at_ms = :nowMs WHERE id = :audioId")
    suspend fun setFavorite(audioId: String, favorite: Boolean, nowMs: Long)

    /** WMA 后台转码成功后回填真实时长；URI 守卫防止旧任务覆盖同 id 的新文件。 */
    @Query(
        "UPDATE audio_book SET duration_ms = :durationMs, duration_read_failed = 0, updated_at_ms = :nowMs " +
            "WHERE id = :audioId AND uri = :uri",
    )
    suspend fun setPreparedDuration(audioId: String, uri: String, durationMs: Long, nowMs: Long): Int

    @Query("DELETE FROM audio_book WHERE id = :audioId")
    suspend fun delete(audioId: String)
}

/** [AudioBookDao.existingForFolder] 的投影 */
data class ExistingAudioRow(
    val id: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val titleIsManual: Boolean,
    val fileMissing: Boolean,
)
