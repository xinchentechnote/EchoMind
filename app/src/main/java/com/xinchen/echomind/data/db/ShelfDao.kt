package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 书架一行需要的全部字段（audio_book LEFT JOIN playback_state） */
data class ShelfRow(
    val id: String,
    val uri: String,
    val title: String,
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val fileStatus: String,
    val transcriptionState: String,
    val isFavorite: Boolean,
    val lastPositionMs: Long,
    val lastPlayedAtMs: Long,
    val playbackSpeed: Float,
)

private const val SHELF_PROJECTION = """
    b.id AS id, b.uri AS uri, b.title AS title, b.file_name AS fileName,
    b.duration_ms AS durationMs, b.size_bytes AS sizeBytes,
    b.file_status AS fileStatus, b.transcription_state AS transcriptionState,
    b.is_favorite AS isFavorite,
    COALESCE(p.last_position_ms, 0) AS lastPositionMs,
    COALESCE(p.last_played_at_ms, 0) AS lastPlayedAtMs,
    COALESCE(p.playback_speed, 1.0) AS playbackSpeed
"""

@Dao
interface ShelfDao {

    @Query(
        """
        SELECT $SHELF_PROJECTION FROM audio_book b
        LEFT JOIN playback_state p ON p.audio_id = b.id
        WHERE b.title LIKE ('%' || :query || '%')
        ORDER BY b.title COLLATE NOCASE ASC
        """,
    )
    fun observeAll(query: String): Flow<List<ShelfRow>>

    @Query(
        """
        SELECT $SHELF_PROJECTION FROM audio_book b
        LEFT JOIN playback_state p ON p.audio_id = b.id
        WHERE b.title LIKE ('%' || :query || '%') AND b.is_favorite = 1
        ORDER BY b.title COLLATE NOCASE ASC
        """,
    )
    fun observeFavorites(query: String): Flow<List<ShelfRow>>

    /** 最近播放：没播过的不出现 */
    @Query(
        """
        SELECT $SHELF_PROJECTION FROM audio_book b
        INNER JOIN playback_state p ON p.audio_id = b.id
        WHERE b.title LIKE ('%' || :query || '%') AND p.last_played_at_ms > 0
        ORDER BY p.last_played_at_ms DESC
        """,
    )
    fun observeRecent(query: String): Flow<List<ShelfRow>>

    /** 「继续上次收听」：全局最近的一条播放记录 */
    @Query(
        """
        SELECT $SHELF_PROJECTION FROM audio_book b
        INNER JOIN playback_state p ON p.audio_id = b.id
        WHERE p.last_played_at_ms > 0 AND b.file_status = 'PRESENT'
        ORDER BY p.last_played_at_ms DESC LIMIT 1
        """,
    )
    fun observeLastPlayed(): Flow<ShelfRow?>
}
