package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每首音频一行的播放记忆与统计缓存（与 audio_book 一对一：audio_id 同时是 PK 和 FK）。
 *
 * play_count / completed_count / listened_ms_total 是**缓存值**，真源是 listening_session，
 * 可用 PlaybackRepository.recomputeAggregates 重算对账（FR-410）。
 */
@Entity(
    tableName = "playback_state",
    primaryKeys = ["audio_id"],
    foreignKeys = [
        ForeignKey(
            entity = AudioBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["audio_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["last_played_at_ms"])],
)
data class PlaybackStateEntity(
    @ColumnInfo(name = "audio_id") val audioId: String,
    @ColumnInfo(name = "last_position_ms") val lastPositionMs: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
    @ColumnInfo(name = "completed_count") val completedCount: Int = 0,
    @ColumnInfo(name = "listened_ms_total") val listenedMsTotal: Long = 0,
    @ColumnInfo(name = "last_played_at_ms") val lastPlayedAtMs: Long = 0,
    @ColumnInfo(name = "last_completed_at_ms") val lastCompletedAtMs: Long = 0,
    @ColumnInfo(name = "playback_speed") val playbackSpeed: Float = 1.0f,
    @ColumnInfo(name = "is_finished") val isFinished: Boolean = false,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
