package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一本书（一个音频文件）。表名 audio_book。
 *
 * 唯一约束在 uri：重复扫描不能建新记录（FR-106）。
 * fileStatus / transcriptionState 用 TEXT 存枚举名，避免 Room 内置转换器之外的反射成本。
 */
@Entity(
    tableName = "audio_book",
    foreignKeys = [
        ForeignKey(
            entity = ImportedFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["folder_id"]),
        Index(value = ["title"]),
        Index(value = ["transcription_state"]),
    ],
)
data class AudioBookEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    /** 相对所选树根的路径，用于展示层级与稳定排序 */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** 文件名（含扩展名），扫描时刷新 */
    @ColumnInfo(name = "file_name") val fileName: String,
    /** 展示标题：默认由文件名推导，用户改过就不再覆盖 */
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "title_is_manual") val titleIsManual: Boolean = false,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "file_last_modified_ms") val fileLastModifiedMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    /** 时长是否读取成功；0 且 read_failed 时 UI 显示「时长未知」而不是 0:00 */
    @ColumnInfo(name = "duration_read_failed") val durationReadFailed: Boolean = false,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "file_status") val fileStatus: String = FileStatus.Present,
    @ColumnInfo(name = "transcription_state") val transcriptionState: String = TranscriptionState.None,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long,
    @ColumnInfo(name = "last_scanned_at_ms") val lastScannedAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    object FileStatus {
        const val Present = "PRESENT"
        const val Missing = "MISSING"
    }

    object TranscriptionState {
        const val None = "NONE"
        const val Queued = "QUEUED"
        const val Running = "RUNNING"
        const val Ready = "READY"
        const val Failed = "FAILED"
        /** 文件内容变了（size/mtime），旧字幕不再可信 */
        const val Expired = "EXPIRED"
    }
}
