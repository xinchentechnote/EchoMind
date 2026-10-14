package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xinchen.echomind.domain.model.SessionStatus

/**
 * 一次收听会话（真源：统计全部由这张表推导，`playback_state` 里的计数只是缓存）。
 *
 * **没有外键**：从书架删除书籍不得删除收听历史（AGENTS.md §4）。
 * `ended_at_ms = 0` 是崩溃检测锚点：启动时把这种行按最后一次 flush 的数值结算为 CRASHED。
 */
@Entity(
    tableName = "listening_session",
    indices = [
        Index(value = ["audio_id"]),
        Index(value = ["started_at_ms"]),
        Index(value = ["session_status"]),
    ],
)
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "audio_id") val audioId: String,
    @ColumnInfo(name = "audio_title_snapshot") val audioTitleSnapshot: String,
    @ColumnInfo(name = "duration_ms_snapshot") val durationMsSnapshot: Long,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    /** 0 = 仍在进行（ACTIVE）*/
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long = 0,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long = 0,
    @ColumnInfo(name = "consumed_audio_ms") val consumedAudioMs: Long = 0,
    @ColumnInfo(name = "start_position_ms") val startPositionMs: Long = 0,
    @ColumnInfo(name = "end_position_ms") val endPositionMs: Long = 0,
    @ColumnInfo(name = "max_reached_position_ms") val maxReachedPositionMs: Long = 0,
    @ColumnInfo(name = "completed") val completed: Boolean = false,
    @ColumnInfo(name = "is_counted") val isCounted: Boolean = false,
    @ColumnInfo(name = "session_status") val sessionStatus: String = Status.ACTIVE,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    object Status {
        const val ACTIVE = "ACTIVE"
        const val FINISHED = "FINISHED"
        const val INTERRUPTED = "INTERRUPTED"
        const val CRASHED = "CRASHED"
        const val SKIPPED_TOO_SHORT = "SKIPPED_TOO_SHORT"
    }
}

fun SessionStatus.toDbString() = when (this) {
    SessionStatus.Active -> ListeningSessionEntity.Status.ACTIVE
    SessionStatus.Finished -> ListeningSessionEntity.Status.FINISHED
    SessionStatus.Interrupted -> ListeningSessionEntity.Status.INTERRUPTED
    SessionStatus.Crashed -> ListeningSessionEntity.Status.CRASHED
    SessionStatus.SkippedTooShort -> ListeningSessionEntity.Status.SKIPPED_TOO_SHORT
}
