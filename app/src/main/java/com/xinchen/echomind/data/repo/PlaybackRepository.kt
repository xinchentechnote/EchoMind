package com.xinchen.echomind.data.repo

import androidx.room.withTransaction
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ListeningSessionEntity
import com.xinchen.echomind.data.db.PlaybackStateEntity
import com.xinchen.echomind.data.db.RecentSessionRow
import com.xinchen.echomind.data.db.toDbString
import com.xinchen.echomind.domain.model.ListeningSessionData
import com.xinchen.echomind.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map

/** 统计页的全局数字（FR-411），一律从 listening_session 真源算出，不读聚合缓存 */
data class PlaybackStats(
    val totalListenedMs: Long,
    val totalPlayCount: Int,
    val totalCompletedCount: Int,
)

interface PlaybackRepository {
    fun snapshot(audioId: String): Flow<PlaybackStateSnapshot>
    fun stats(): Flow<PlaybackStats>
    fun recentSessions(limit: Int): Flow<List<RecentSessionRow>>

    /** 会话开始：落一行 ACTIVE（崩溃检测锚点） */
    suspend fun openSession(audioId: String, title: String, startPositionMs: Long, durationMs: Long)

    /** 节流的进度落盘：会话行覆盖写 + playback_state 位置（FR-412） */
    suspend fun flushProgress(progress: PlaybackProgress, speed: Float)

    /** 会话结算：同一事务里写会话行 + 增量维护聚合缓存（FR-409/410） */
    suspend fun settleSession(session: ListeningSessionData, speed: Float)

    suspend fun saveSpeed(audioId: String, speed: Float)

    /**
     * 把上次进程留下的 ACTIVE 行按最后 flush 结算为 CRASHED 并重算聚合（FR-405）。
     * 幂等：第一次写库前自动执行，所以即使用户在启动瞬间就点了播放也不会漏掉恢复。
     */
    suspend fun recoverCrashedSessions(): Int

    /** 设置页「重算统计」：从会话表覆盖聚合缓存（FR-410/905） */
    suspend fun recomputeAggregates()
}

/** UI 需要的播放记忆快照（不含 Room 实体类型，便于 domain/UI 与数据层解耦） */
data class PlaybackStateSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val playCount: Int,
    val completedCount: Int,
    val listenedMsTotal: Long,
    val lastPlayedAtMs: Long,
    val finished: Boolean,
)

/**
 * 播放记忆与统计的落库层。
 *
 * 写路径都在事务里：「会话行 + 聚合缓存」必须同时生效，只写一半会让 FR-410 的对账永远不平。
 * 任何写之前都先 [ensureRecovered]：崩溃恢复必须在「开新会话」之前完成，否则残留的 ACTIVE 行
 * 会被后来的会话一起结算掉（用户在启动瞬间就点播放是完全正常的路径）。
 */
class PlaybackRepositoryImpl(
    private val db: EchoMindDatabase,
    private val clock: TimeSource,
) : PlaybackRepository {

    private val sessionDao get() = db.listeningSessionDao()
    private val stateDao get() = db.playbackStateDao()
    private val audioDao get() = db.audioBookDao()

    private val recoveryLock = Mutex()
    private var recovered = false

    override fun snapshot(audioId: String): Flow<PlaybackStateSnapshot> =
        stateDao.observe(audioId).mapSnapshot()

    override fun stats(): Flow<PlaybackStats> = combine(
        sessionDao.observeTotalListenedMs(),
        sessionDao.observeTotalPlayCount(),
        sessionDao.observeTotalCompletedCount(),
    ) { listenedMs, plays, completions -> PlaybackStats(listenedMs, plays, completions) }

    override fun recentSessions(limit: Int): Flow<List<RecentSessionRow>> = sessionDao.observeRecent(limit)

    override suspend fun openSession(audioId: String, title: String, startPositionMs: Long, durationMs: Long) {
        ensureRecovered()
        db.withTransaction {
            val now = clock.wallMs()
            ensureStateRow(audioId, now)
            // 同一首已有 ACTIVE 行时不再重复开：双击同一本书不该产出两条会话
            if (sessionDao.countOpen(audioId) == 0) {
                sessionDao.insert(
                    ListeningSessionEntity(
                        audioId = audioId,
                        audioTitleSnapshot = title,
                        durationMsSnapshot = durationMs,
                        startedAtMs = now,
                        startPositionMs = startPositionMs,
                        endPositionMs = startPositionMs,
                        maxReachedPositionMs = startPositionMs,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ),
                )
            }
            stateDao.saveProgress(
                audioId = audioId,
                positionMs = startPositionMs,
                durationMs = durationMs,
                speed = speedOf(audioId),
                lastPlayedAtMs = now,
                nowMs = now,
                finished = false,
            )
        }
    }

    override suspend fun flushProgress(progress: PlaybackProgress, speed: Float) {
        ensureRecovered()
        db.withTransaction {
            val now = clock.wallMs()
            ensureStateRow(progress.audioId, now)
            val written = sessionDao.flushOpen(
                audioId = progress.audioId,
                listenedMs = progress.listenedMs,
                consumedAudioMs = progress.consumedAudioMs,
                endPositionMs = progress.positionMs,
                maxReachedPositionMs = progress.maxReachedPositionMs,
                durationMs = progress.durationMs,
                nowMs = now,
            )
            if (written == 0) {
                // 没走 openSession 的直接落库路径（例如服务被拉起后立刻 flush）：补一行 ACTIVE
                sessionDao.insert(
                    ListeningSessionEntity(
                        audioId = progress.audioId,
                        audioTitleSnapshot = audioDao.getById(progress.audioId)?.title.orEmpty(),
                        durationMsSnapshot = progress.durationMs,
                        startedAtMs = now,
                        listenedMs = progress.listenedMs,
                        consumedAudioMs = progress.consumedAudioMs,
                        startPositionMs = progress.positionMs,
                        endPositionMs = progress.positionMs,
                        maxReachedPositionMs = progress.maxReachedPositionMs,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ),
                )
            }
            stateDao.saveProgress(
                audioId = progress.audioId,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                speed = speed,
                lastPlayedAtMs = now,
                nowMs = now,
                finished = isFinished(progress.durationMs, progress.positionMs),
            )
        }
    }

    override suspend fun settleSession(session: ListeningSessionData, speed: Float) {
        ensureRecovered()
        db.withTransaction {
            val now = clock.wallMs()
            val written = sessionDao.settleOpen(
                audioId = session.audioId,
                listenedMs = session.listenedMs,
                consumedAudioMs = session.consumedAudioMs,
                endPositionMs = session.endPositionMs,
                maxReachedPositionMs = session.maxReachedPositionMs,
                durationMsSnapshot = session.durationMsSnapshot,
                titleSnapshot = session.audioTitleSnapshot,
                endedAtMs = session.endedAtMs,
                completed = session.completed,
                isCounted = session.counted,
                status = session.status.toDbString(),
                nowMs = now,
            )
            if (written == 0) sessionDao.insert(session.toEntity(now))
            ensureStateRow(session.audioId, now)
            stateDao.applySettledSession(
                audioId = session.audioId,
                playCountInc = if (session.counted) 1 else 0,
                completedCountInc = if (session.completed) 1 else 0,
                listenedMs = session.listenedMs,
                endedAtMs = session.endedAtMs,
                nowMs = now,
            )
            stateDao.saveProgress(
                audioId = session.audioId,
                positionMs = session.endPositionMs,
                durationMs = session.durationMsSnapshot,
                speed = speed,
                lastPlayedAtMs = session.endedAtMs,
                nowMs = now,
                finished = isFinished(session.durationMsSnapshot, session.endPositionMs),
            )
        }
    }

    override suspend fun saveSpeed(audioId: String, speed: Float) {
        ensureRecovered()
        db.withTransaction {
            val now = clock.wallMs()
            ensureStateRow(audioId, now)
            stateDao.saveSpeed(audioId, speed, now)
        }
    }

    override suspend fun recoverCrashedSessions(): Int = ensureRecovered()

    override suspend fun recomputeAggregates() {
        db.withTransaction { stateDao.recomputeAll() }
    }

    /**
     * 崩溃恢复（FR-405）：把上一次进程留下的 ACTIVE 行按最后 flush 的数值结算为 CRASHED，
     * 然后整段重算聚合——崩溃前哪些会话已经计入增量无法确定，重算是唯一能对平的做法。
     * 只做一次（本进程生命周期内）。
     */
    private suspend fun ensureRecovered(): Int = recoveryLock.withLock {
        if (recovered) return@withLock 0
        val count = db.withTransaction {
            sessionDao.settleOpenAsCrashed(PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS)
        }
        recomputeAggregates()
        recovered = true
        count
    }

    private suspend fun ensureStateRow(audioId: String, nowMs: Long) {
        stateDao.ensureRow(PlaybackStateEntity(audioId = audioId, createdAtMs = nowMs, updatedAtMs = nowMs))
    }

    private suspend fun speedOf(audioId: String): Float =
        stateDao.get(audioId)?.playbackSpeed ?: PlaybackPolicy.DEFAULT_SPEED

    private fun ListeningSessionData.toEntity(nowMs: Long) = ListeningSessionEntity(
        audioId = audioId,
        audioTitleSnapshot = audioTitleSnapshot,
        durationMsSnapshot = durationMsSnapshot,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        listenedMs = listenedMs,
        consumedAudioMs = consumedAudioMs,
        startPositionMs = startPositionMs,
        endPositionMs = endPositionMs,
        maxReachedPositionMs = maxReachedPositionMs,
        completed = completed,
        isCounted = counted,
        sessionStatus = status.toDbString(),
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )

    private fun isFinished(durationMs: Long, positionMs: Long) =
        durationMs > 0 && positionMs >= durationMs - PlaybackPolicy.END_TOLERANCE_MS
}

private fun Flow<PlaybackStateEntity?>.mapSnapshot(): Flow<PlaybackStateSnapshot> =
    map { row ->
        row?.let {
            PlaybackStateSnapshot(
                positionMs = it.lastPositionMs,
                durationMs = it.durationMs,
                speed = it.playbackSpeed,
                playCount = it.playCount,
                completedCount = it.completedCount,
                listenedMsTotal = it.listenedMsTotal,
                lastPlayedAtMs = it.lastPlayedAtMs,
                finished = it.isFinished,
            )
        } ?: PlaybackStateSnapshot(0, 0, PlaybackPolicy.DEFAULT_SPEED, 0, 0, 0, 0, false)
    }
