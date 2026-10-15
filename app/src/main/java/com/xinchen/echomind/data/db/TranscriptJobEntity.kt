package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次转录任务（`docs/database-design.md` §6）。
 *
 * 一张表多行、按时间累积：同一个 audio 重试会产生新行，旧行留着当错误上下文——
 * 用户问「上次为什么失败」时得有东西可查。
 *
 * 状态跃迁与 `audio_book.transcription_state` 的更新**必须在同一个事务里**（AGENTS.md §4），
 * 所以跃迁不写在 DAO 的裸方法上，而由 `TranscriptRepositoryImpl` 包 `withTransaction` 完成。
 */
@Entity(
    tableName = "transcript_job",
    foreignKeys = [
        ForeignKey(
            entity = AudioBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["audio_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["audio_id"]),
        Index(value = ["status"]),
        Index(value = ["audio_id", "created_at_ms"]),
    ],
)
data class TranscriptJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "audio_id") val audioId: String,
    /** 跑这个任务用的是清单里哪个模型（`ModelCatalog.paraformer.id`），字幕的「谁产出的」就记在这儿 */
    @ColumnInfo(name = "model_id") val modelId: String,
    /**
     * 模型权重身份 = `model.int8.onnx` 的 SHA-256 前 12 位（`ModelCatalog` 里钉住的那串，不是编出来的版本号）。
     * 同一份权重换 token 表也算换版本，所以这里记的是模型文件本身。
     */
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "status") val status: String = Status.Queued,
    /** 分段总数：要 VAD 跑完才知道，入队时是 0 */
    @ColumnInfo(name = "total_segments") val totalSegments: Int = 0,
    @ColumnInfo(name = "done_segments") val doneSegments: Int = 0,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    /** 面向用户的说明，直接来自 `AsrModelNotReadyException` / `PcmDecodeException` 的原文 */
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    /** WorkManager 的请求 id，取消与重启对账用；没进队列前为 null */
    @ColumnInfo(name = "work_request_id") val workRequestId: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long? = null,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long? = null,
) {
    object Status {
        const val Queued = "QUEUED"
        const val Running = "RUNNING"
        const val Success = "SUCCESS"
        const val Failed = "FAILED"
        const val Cancelled = "CANCELLED"
    }

    object ErrorCode {
        const val ModelMissing = "MODEL_MISSING"
        const val DecodeFailed = "DECODE_FAILED"
        const val OutOfMemory = "OUT_OF_MEMORY"
        const val PermissionRevoked = "PERMISSION_REVOKED"
        const val Cancelled = "CANCELLED"

        /** 进程被杀 / 系统重启时任务还挂在 RUNNING，恢复时标这个（不是模型或文件出了问题） */
        const val Interrupted = "INTERRUPTED"
        const val Unknown = "UNKNOWN"
    }
}
