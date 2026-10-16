package com.xinchen.echomind.data.repo

import androidx.room.withTransaction
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.SubtitleSegmentEntity
import com.xinchen.echomind.data.db.TranscriptJobEntity
import com.xinchen.echomind.domain.model.EnqueueOutcome
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 转录任务的持久化实现（`docs/database-design.md` §6）。
 *
 * 每条跃迁都是「改 `transcript_job` + 改 `audio_book.transcription_state`」两笔写，放在一个
 * `withTransaction` 里（AGENTS.md §4）：只写成功一半的话，书架显示的「已转录」就和库里有没有字幕脱节。
 *
 * `transcript_job` 的更新语句自带 `WHERE status IN (...)` 守卫，返回 0 行就是跃迁非法（重复结算、
 * 已取消却被 worker 写回），这里把它翻译成 `false` 让调用方看得见，而不是抛异常打断 worker。
 * 但 `commitSubtitles` 是**只有成功路径**的收尾，0 行意味着这批字幕不该再落库，所以那里用 `check`
 * 抛出，事务整体回滚——宁可一句字幕都没写，也不能写完字幕却留着个 RUNNING 的任务。
 */
class TranscriptRepositoryImpl(
    private val db: EchoMindDatabase,
    private val clock: TimeSource,
) : TranscriptRepository {

    private val jobDao = db.transcriptJobDao()
    private val bookDao = db.audioBookDao()
    private val subtitleDao = db.subtitleSegmentDao()

    override fun jobs(): Flow<List<TranscriptJobRow>> =
        jobDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeActiveJob(audioId: String): Flow<TranscriptJobRow?> =
        jobDao.observeActiveOf(audioId).map { it?.toDomain() }

    override suspend fun enqueue(audioId: String, model: TranscriptModelRef, force: Boolean): EnqueueOutcome =
        db.withTransaction {
            val book = bookDao.getById(audioId) ?: return@withTransaction EnqueueOutcome.AudioNotFound
            jobDao.activeOf(audioId)?.let { return@withTransaction EnqueueOutcome.AlreadyActive(it.id) }
            // READY 且字幕真在库里才算转好了；只剩手工测试字幕（source 不是 ASR）时状态可能是过期标记，
            // 拦不住重新转录
            if (!force && book.transcriptionState == AudioBookEntity.TranscriptionState.Ready &&
                subtitleDao.countAsrOf(audioId) > 0
            ) {
                return@withTransaction EnqueueOutcome.AlreadyTranscribed
            }

            val now = clock.wallMs()
            val jobId = UUID.randomUUID().toString()
            jobDao.insert(
                TranscriptJobEntity(
                    id = jobId,
                    audioId = audioId,
                    modelId = model.id,
                    modelVersion = model.version,
                    createdAtMs = now,
                ),
            )
            bookDao.setTranscriptionState(listOf(audioId), AudioBookEntity.TranscriptionState.Queued, now)
            EnqueueOutcome.Created(jobId)
        }

    override suspend fun cancel(jobId: String): Boolean = db.withTransaction {
        val job = jobDao.getById(jobId) ?: return@withTransaction false
        val now = clock.wallMs()
        if (jobDao.markCancelled(jobId, now, "用户取消") == 0) return@withTransaction false
        bookDao.setTranscriptionState(listOf(job.audioId), stateAfterRollback(job.audioId), now)
        true
    }

    /**
     * 重试 = 对同一本书再入队一次（`force=true`，因为上一轮刚失败/取消时状态不是 READY）。
     * 旧行不动，新一轮是新的一行——失败历史留得住（database-design.md §6）。
     */
    override suspend fun retry(jobId: String, model: TranscriptModelRef): EnqueueOutcome {
        val audioId = jobDao.getById(jobId)?.audioId ?: return EnqueueOutcome.JobNotFound
        return enqueue(audioId, model, force = true)
    }

    override suspend fun attachWorkRequest(jobId: String, workRequestId: String): Boolean =
        jobDao.setWorkRequestId(jobId, workRequestId) > 0

    override suspend fun jobById(jobId: String): TranscriptJobRow? = jobDao.getById(jobId)?.toDomain()

    override suspend fun updateModel(jobId: String, model: TranscriptModelRef): Boolean =
        jobDao.updateModel(jobId, model.id, model.version) > 0

    override suspend fun markRunning(jobId: String, totalSegments: Int): Boolean = db.withTransaction {
        val job = jobDao.getById(jobId) ?: return@withTransaction false
        val now = clock.wallMs()
        if (jobDao.markRunning(jobId, now, totalSegments) == 0) return@withTransaction false
        bookDao.setTranscriptionState(listOf(job.audioId), AudioBookEntity.TranscriptionState.Running, now)
        true
    }

    override suspend fun updateProgress(jobId: String, doneSegments: Int, totalSegments: Int): Boolean =
        jobDao.updateProgress(jobId, doneSegments, totalSegments) > 0

    override suspend fun commitSubtitles(jobId: String, segments: List<SubtitleSegment>): Int =
        db.withTransaction {
            require(segments.isNotEmpty()) { "空字幕不能标成转录成功" }
            val job = jobDao.getById(jobId) ?: error("任务不存在，无法提交字幕：$jobId")
            val now = clock.wallMs()

            subtitleDao.deleteByAudio(job.audioId)
            subtitleDao.insertAll(
                segments.mapIndexed { index, segment ->
                    SubtitleSegmentEntity(
                        audioId = job.audioId,
                        segmentIndex = index,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = segment.text,
                        source = SubtitleSegmentEntity.Source.ASR,
                        jobId = jobId,
                        createdAtMs = now,
                    )
                },
            )
            // 分母沿用 VAD 那轮定下的段数：一句字幕可能来自多块，把分母改成句数会画出倒退的进度
            val total = job.totalSegments
            check(jobDao.markSuccess(jobId, now, doneSegments = total, totalSegments = total) > 0) {
                "任务 $jobId 不在 RUNNING，字幕已回滚"
            }
            bookDao.setTranscriptionState(listOf(job.audioId), AudioBookEntity.TranscriptionState.Ready, now)
            segments.size
        }

    override suspend fun markFailed(jobId: String, failure: TranscriptFailure, message: String?): Boolean =
        db.withTransaction {
            val job = jobDao.getById(jobId) ?: return@withTransaction false
            val now = clock.wallMs()
            if (jobDao.markFailed(jobId, now, failure.toDbString(), message) == 0) return@withTransaction false
            bookDao.setTranscriptionState(listOf(job.audioId), AudioBookEntity.TranscriptionState.Failed, now)
            true
        }

    override suspend fun recoverInterrupted(): Int = db.withTransaction {
        val audioIds = jobDao.activeAudioIds()
        if (audioIds.isEmpty()) return@withTransaction 0
        val now = clock.wallMs()
        val rows = jobDao.markInterrupted(now)
        bookDao.setTranscriptionState(audioIds, AudioBookEntity.TranscriptionState.Failed, now)
        rows
    }

    /**
     * 任务被取消后书架该显示什么。字幕没被动过（删旧只发生在 commitSubtitles 的事务里），所以：
     * - 库里还有 ASR 字幕且比当前文件旧 → EXPIRED，这是 `AudioRepositoryImpl` 扫描时同一套判据
     *   （FR-107：文件变了旧字幕不可信），不能因为取消就谎报 READY；
     * - 还有 ASR 字幕且不比文件旧 → READY；
     * - 没有 ASR 字幕 → NONE（只有手工测试字幕也算没有：那不是 ASR 产物，AGENTS.md §5）。
     */
    private suspend fun stateAfterRollback(audioId: String): String {
        if (subtitleDao.countAsrOf(audioId) == 0) return AudioBookEntity.TranscriptionState.None
        val newestSubtitleMs = subtitleDao.latestAsrCreatedAtMs(audioId) ?: return AudioBookEntity.TranscriptionState.None
        val book = bookDao.getById(audioId) ?: return AudioBookEntity.TranscriptionState.None
        return if (newestSubtitleMs >= book.fileLastModifiedMs) {
            AudioBookEntity.TranscriptionState.Ready
        } else {
            AudioBookEntity.TranscriptionState.Expired
        }
    }
}

private fun TranscriptJobEntity.toDomain() = TranscriptJobRow(
    id = id,
    audioId = audioId,
    modelId = modelId,
    modelVersion = modelVersion,
    status = status.toDomainStatus(),
    totalSegments = totalSegments,
    doneSegments = doneSegments,
    failure = errorCode?.toDomainFailure(),
    errorMessage = errorMessage,
    createdAtMs = createdAtMs,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    workRequestId = workRequestId,
)

private fun String.toDomainStatus(): TranscriptJobStatus = when (this) {
    TranscriptJobEntity.Status.Queued -> TranscriptJobStatus.Queued
    TranscriptJobEntity.Status.Running -> TranscriptJobStatus.Running
    TranscriptJobEntity.Status.Success -> TranscriptJobStatus.Success
    TranscriptJobEntity.Status.Failed -> TranscriptJobStatus.Failed
    TranscriptJobEntity.Status.Cancelled -> TranscriptJobStatus.Cancelled
    else -> error("transcript_job.status 里有未知值：$this")
}

private fun String.toDomainFailure(): TranscriptFailure? = when (this) {
    TranscriptJobEntity.ErrorCode.ModelMissing -> TranscriptFailure.ModelMissing
    TranscriptJobEntity.ErrorCode.DecodeFailed -> TranscriptFailure.DecodeFailed
    TranscriptJobEntity.ErrorCode.OutOfMemory -> TranscriptFailure.OutOfMemory
    TranscriptJobEntity.ErrorCode.PermissionRevoked -> TranscriptFailure.PermissionRevoked
    TranscriptJobEntity.ErrorCode.OutputUnreliable -> TranscriptFailure.OutputUnreliable
    TranscriptJobEntity.ErrorCode.Cancelled -> TranscriptFailure.Cancelled
    TranscriptJobEntity.ErrorCode.Interrupted -> TranscriptFailure.Interrupted
    TranscriptJobEntity.ErrorCode.Unknown -> TranscriptFailure.Unknown
    else -> null
}

private fun TranscriptFailure.toDbString(): String = when (this) {
    TranscriptFailure.ModelMissing -> TranscriptJobEntity.ErrorCode.ModelMissing
    TranscriptFailure.DecodeFailed -> TranscriptJobEntity.ErrorCode.DecodeFailed
    TranscriptFailure.OutOfMemory -> TranscriptJobEntity.ErrorCode.OutOfMemory
    TranscriptFailure.PermissionRevoked -> TranscriptJobEntity.ErrorCode.PermissionRevoked
    TranscriptFailure.OutputUnreliable -> TranscriptJobEntity.ErrorCode.OutputUnreliable
    TranscriptFailure.Cancelled -> TranscriptJobEntity.ErrorCode.Cancelled
    TranscriptFailure.Interrupted -> TranscriptJobEntity.ErrorCode.Interrupted
    TranscriptFailure.Unknown -> TranscriptJobEntity.ErrorCode.Unknown
}
