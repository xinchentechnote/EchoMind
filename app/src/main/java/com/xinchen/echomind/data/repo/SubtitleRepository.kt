package com.xinchen.echomind.data.repo

import androidx.room.withTransaction
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.SubtitleSegmentEntity
import com.xinchen.echomind.domain.model.SubtitleSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 字幕的读写入口。UI 与 ViewModel 只经这里，不碰 DAO（AGENTS.md §3）。
 *
 * `transcription_state` 不在本类的职责里：那一列描述的是**转录任务**的结果，归 Phase 6 的任务管线维护。
 * 手工导入的测试字幕只写 `subtitle_segment`，所以书架上那本书仍然显示「未转录」——它确实没被转录过，
 * 只是有一副测试字幕可读（AGENTS.md §5）。
 */
interface SubtitleRepository {

    /** 按 `segment_index` 升序透出（时间序 = 索引序，docs/database-design.md §5） */
    fun observeSegments(audioId: String): Flow<List<SubtitleSegment>>

    fun observeSummary(audioId: String): Flow<SubtitleSummary>

    /**
     * 整本替换（FR-605）：删旧 + 插新在**同一个事务**里，失败就整体回滚，
     * 否则中途崩溃会让用户看到半本新半本旧的字幕拼在一起。
     *
     * [segments] 必须已按时间升序；落库时 `segment_index` 按传入顺序重编，保证连续。
     * 返回写入的句数。
     */
    suspend fun replace(audioId: String, segments: List<SubtitleSegment>, source: SubtitleSource): Int

    suspend fun clear(audioId: String): Int
}

/** 字幕来源。DB 里存的是字符串常量（[com.xinchen.echomind.data.db.SubtitleSegmentEntity.Source]），转换在本类实现里完成 */
enum class SubtitleSource {

    /** 本地 ASR 产物 */
    ASR,

    /** 手工导入的测试字幕：只验证字幕管线，UI 必须标明它不是 ASR 产物（AGENTS.md §5） */
    MANUAL_TEST,
}

/** 播放页决定「显示窗口 / 提示还没有字幕 / 标注非 ASR 产物」用的三个数 */
data class SubtitleSummary(
    val count: Int,
    val producedByAsr: Boolean,
)

class SubtitleRepositoryImpl(
    private val db: EchoMindDatabase,
    private val clock: TimeSource,
) : SubtitleRepository {

    private val dao = db.subtitleSegmentDao()

    override fun observeSegments(audioId: String): Flow<List<SubtitleSegment>> =
        dao.observeByAudio(audioId).toDomainFlow()

    override fun observeSummary(audioId: String): Flow<SubtitleSummary> =
        combine(dao.observeCount(audioId), dao.observeSources(audioId)) { count, sources ->
            SubtitleSummary(count, sources.contains(SubtitleSegmentEntity.Source.ASR))
        }

    override suspend fun replace(audioId: String, segments: List<SubtitleSegment>, source: SubtitleSource): Int =
        db.withTransaction {
            dao.deleteByAudio(audioId)
            val now = clock.wallMs()
            dao.insertAll(
                segments.mapIndexed { index, segment ->
                    SubtitleSegmentEntity(
                        audioId = audioId,
                        segmentIndex = index,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = segment.text,
                        source = source.toDbString(),
                        createdAtMs = now,
                    )
                },
            )
            segments.size
        }

    override suspend fun clear(audioId: String): Int = db.withTransaction { dao.deleteByAudio(audioId) }
}

private fun SubtitleSource.toDbString(): String = when (this) {
    SubtitleSource.ASR -> SubtitleSegmentEntity.Source.ASR
    SubtitleSource.MANUAL_TEST -> SubtitleSegmentEntity.Source.MANUAL_TEST
}

private fun Flow<List<SubtitleSegmentEntity>>.toDomainFlow(): Flow<List<SubtitleSegment>> =
    map { list -> list.map { it.toDomain() } }

private fun SubtitleSegmentEntity.toDomain() = SubtitleSegment(
    index = segmentIndex,
    startMs = startMs,
    endMs = endMs,
    text = text,
)
