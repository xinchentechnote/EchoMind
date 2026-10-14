package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一句字幕（时间序 = `segment_index` 序）。
 *
 * 时间戳的诚实性（AGENTS.md §5）：`start_ms`/`end_ms` 只能来自模型返回的时间信息或明确记录误差的
 * 对齐结果，**禁止**按字数均分、复制文本凑时间。所以来源记在 `source` 列里，UI 按它标注，
 * 测试字幕永远不会被当成 ASR 产物显示。
 *
 * `job_id` 这一版**不加外键**：`transcript_job` 表要 Phase 6 才存在，SQLite 后补 FK 得整表重建
 * （建新表→拷贝→改名→重建索引）。现在建成可空 TEXT，Phase 6 建任务表时用一次 v2→v3 的重建补上 FK，
 * 理由记在 `docs/database-design.md` §5。
 */
@Entity(
    tableName = "subtitle_segment",
    foreignKeys = [
        ForeignKey(
            entity = AudioBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["audio_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["audio_id", "segment_index"], unique = true),
        Index(value = ["audio_id", "start_ms"]),
    ],
)
data class SubtitleSegmentEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "audio_id") val audioId: String,
    @ColumnInfo(name = "segment_index") val segmentIndex: Int,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "source") val source: String = Source.ASR,
    /** 产出这一批的字段的行，Phase 6 才有真值；可空且暂无 FK（见类注释） */
    @ColumnInfo(name = "job_id") val jobId: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    object Source {
        const val ASR = "ASR"

        /** 手工导入的测试字幕：只用来验证字幕管线，必须在 UI 上标明不是 ASR 产物 */
        const val MANUAL_TEST = "MANUAL_TEST"
    }
}
