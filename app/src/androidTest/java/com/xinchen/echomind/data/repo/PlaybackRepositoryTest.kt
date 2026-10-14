package com.xinchen.echomind.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ListeningSessionEntity
import com.xinchen.echomind.domain.model.ListeningSessionData
import com.xinchen.echomind.domain.model.PlaybackProgress
import com.xinchen.echomind.domain.model.SessionEndReason
import com.xinchen.echomind.domain.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 会话落库与统计聚合的仪器化测试（真 SQLite：相关子查询、级联删除、事务都得真跑）。
 *
 * 时钟可注入，所以「flush 覆盖写不累加」「崩溃恢复按最后一次 flush 结算」这类
 * 只有多次写入才看得出性质的断言才能确定性地成立。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRepositoryTest {

    private class FakeClock : TimeSource {
        var monotonic = 0L
        var wall = 1_700_000_000_000L
        override fun monotonicMs(): Long = monotonic
        override fun wallMs(): Long = wall
    }

    private lateinit var db: EchoMindDatabase
    private lateinit var clock: FakeClock
    private lateinit var repo: PlaybackRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        clock = FakeClock()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EchoMindDatabase::class.java,
        ).build()
        repo = PlaybackRepositoryImpl(db, clock)
        db.importedFolderDao().upsert(
            ImportedFolderEntity(id = "folder-1", treeUri = "tree://music", displayName = "Music", grantedAtMs = 1L),
        )
        seedBook("a", "第一首")
        seedBook("b", "第二首")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(id: String, title: String) {
        db.audioBookDao().upsert(
            AudioBookEntity(
                id = id,
                uri = "uri://$id",
                folderId = "folder-1",
                relativePath = "$id.mp3",
                fileName = "$id.mp3",
                title = title,
                sizeBytes = 1_000,
                fileLastModifiedMs = 1,
                durationMs = DURATION,
                addedAtMs = 1,
                lastScannedAtMs = 1,
                updatedAtMs = 1,
            ),
        )
    }

    /**
     * 构造一个「已经由 tracker 算好」的会话数据，直接喂给结算路径。
     * 默认 started = 结束时刻 - listened，与真实 tracker 的输出形状一致。
     */
    private fun session(
        audioId: String,
        listened: Long,
        consumed: Long = listened,
        endPosition: Long = listened,
        duration: Long = DURATION,
        completed: Boolean = false,
        reason: SessionEndReason = SessionEndReason.UserStopped,
        started: Long = clock.wall - listened,
    ) = ListeningSessionData(
        audioId = audioId,
        audioTitleSnapshot = "标题$audioId",
        durationMsSnapshot = duration,
        startedAtMs = started,
        endedAtMs = clock.wall,
        listenedMs = listened,
        consumedAudioMs = consumed,
        startPositionMs = 0,
        endPositionMs = endPosition,
        maxReachedPositionMs = endPosition,
        completed = completed,
        counted = listened >= PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS,
        status = when {
            listened < PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS -> SessionStatus.SkippedTooShort
            reason == SessionEndReason.Natural -> SessionStatus.Finished
            else -> SessionStatus.Interrupted
        },
    )

    private fun progress(audioId: String, position: Long, listened: Long, consumed: Long) = PlaybackProgress(
        audioId = audioId,
        positionMs = position,
        durationMs = DURATION,
        listenedMs = listened,
        consumedAudioMs = consumed,
        maxReachedPositionMs = position,
    )

    private suspend fun settled(audioId: String) = db.listeningSessionDao().settledOf(audioId)

    @Test
    fun 开场建立活跃会话与播放记忆行() = runBlocking {
        repo.openSession("a", "第一首", 30_000, DURATION)

        val open = db.listeningSessionDao().openSessions()
        assertEquals(1, open.size)
        assertEquals("a", open[0].audioId)
        assertEquals(ListeningSessionEntity.Status.ACTIVE, open[0].sessionStatus)
        assertEquals(0L, open[0].endedAtMs)
        assertEquals(30_000L, open[0].startPositionMs)
        assertEquals("第一首", open[0].audioTitleSnapshot)

        val snapshot = repo.snapshot("a").first()
        assertEquals(30_000L, snapshot.positionMs)
        assertEquals(DURATION, snapshot.durationMs)
        // 刚开始听就进「最近播放」，否则书架排序要等到结算才有值
        assertEquals(clock.wall, snapshot.lastPlayedAtMs)
    }

    /** 同一首重复开场不能产出第二条 ACTIVE 行（双击书架是常见操作） */
    @Test
    fun 同一首重复开场不重复建会话() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.openSession("a", "第一首", 0, DURATION)
        assertEquals(1, db.listeningSessionDao().openSessions().size)
    }

    /** FR-405 的地基：flush 是覆盖写，所以重复 flush 与崩溃恢复都不会把同段时间算两遍 */
    @Test
    fun flush覆盖写累计值而不是累加() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 60_000, listened = 30_000, consumed = 30_000), 1.0f)
        clock.monotonic += 15_000
        repo.flushProgress(progress("a", position = 90_000, listened = 45_000, consumed = 45_000), 1.0f)

        val open = db.listeningSessionDao().openSessions().single()
        assertEquals(45_000L, open.listenedMs)
        assertEquals(45_000L, open.consumedAudioMs)
        assertEquals(90_000L, open.endPositionMs)
        assertEquals(90_000L, repo.snapshot("a").first().positionMs)
    }

    /** 没走过 openSession 的落库路径（服务被系统拉起后直接 flush）要补出会话行 */
    @Test
    fun 没有活跃行时flush会补插会话() = runBlocking {
        repo.flushProgress(progress("a", position = 20_000, listened = 20_000, consumed = 20_000), 1.0f)
        val open = db.listeningSessionDao().openSessions().single()
        assertEquals("a", open.audioId)
        assertEquals(20_000L, open.listenedMs)
        // 标题从书籍记录补齐，不是空串
        assertEquals("第一首", open.audioTitleSnapshot)
    }

    @Test
    fun 结算写最终数值并增量更新聚合缓存() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 120_000, listened = 120_000, consumed = 120_000), 1.0f)
        repo.settleSession(session("a", listened = 120_000), 1.0f)

        assertEquals(emptyList<ListeningSessionEntity>(), db.listeningSessionDao().openSessions())
        val row = settled("a").single()
        assertEquals(120_000L, row.listenedMs)
        assertEquals(ListeningSessionEntity.Status.INTERRUPTED, row.sessionStatus)

        val state = repo.snapshot("a").first()
        assertEquals(1, state.playCount)
        assertEquals(0, state.completedCount)
        assertEquals(120_000L, state.listenedMsTotal)
        assertEquals(clock.wall, state.lastPlayedAtMs)
        assertEquals(120_000L, state.positionMs)
    }

    /** 误触（<5s）只加时长不加次数：口径见 playback-design §3.3 规则 1 */
    @Test
    fun 短会话加时长但不加次数() = runBlocking {
        repo.settleSession(session("a", listened = 3_000), 1.0f)
        val state = repo.snapshot("a").first()
        assertEquals(0, state.playCount)
        assertEquals(3_000L, state.listenedMsTotal)
        assertEquals(ListeningSessionEntity.Status.SKIPPED_TOO_SHORT, settled("a").single().sessionStatus)
    }

    @Test
    fun 完播会话写完成次数与完成时刻() = runBlocking {
        clock.wall += 60_000
        repo.settleSession(
            session("a", listened = DURATION, endPosition = DURATION, completed = true, reason = SessionEndReason.Natural),
            1.0f,
        )

        val state = repo.snapshot("a").first()
        assertEquals(1, state.playCount)
        assertEquals(1, state.completedCount)
        assertTrue(state.finished)
        // 完成时刻 = 会话结束时刻，不是「读到的现在」
        assertEquals(clock.wall, db.playbackStateDao().get("a")!!.lastCompletedAtMs)
        assertEquals(ListeningSessionEntity.Status.FINISHED, settled("a").single().sessionStatus)
    }

    /** FR-409：切歌后上一首已结算、当前首活跃，两行互不干扰 */
    @Test
    fun 切歌留下一条已结算与一条活跃() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 40_000, listened = 40_000, consumed = 40_000), 1.0f)
        repo.settleSession(session("a", listened = 40_000), 1.0f)
        repo.openSession("b", "第二首", 0, DURATION)

        assertEquals(listOf("b"), db.listeningSessionDao().openSessions().map { it.audioId })
        val settledA = settled("a")
        assertEquals(1, settledA.size)
        assertEquals(40_000L, settledA[0].listenedMs)
        assertEquals(ListeningSessionEntity.Status.INTERRUPTED, settledA[0].sessionStatus)
        // b 只开了头，聚合里不该有它的任何数字
        assertEquals(0, repo.snapshot("b").first().playCount)
    }

    /** FR-410：聚合缓存的增量维护与从会话表重算必须等价，这是「重算统计」按钮的正确性前提 */
    @Test
    fun 重算结果与增量维护一致() = runBlocking {
        repo.settleSession(session("a", listened = 60_000), 1.0f)
        clock.wall += 5_000
        repo.settleSession(
            session("a", listened = 120_000, endPosition = DURATION, completed = true, reason = SessionEndReason.Natural),
            1.5f,
        )
        clock.wall += 5_000
        repo.settleSession(session("a", listened = 3_000), 1.0f)
        clock.wall += 5_000
        repo.settleSession(session("b", listened = 90_000), 1.0f)

        // 整行对比：重算不该动位置/时长/倍速/updated_at，只该把统计列拉回真源
        val before = mapOf("a" to db.playbackStateDao().get("a"), "b" to db.playbackStateDao().get("b"))
        repo.recomputeAggregates()
        for (id in listOf("a", "b")) {
            assertEquals("重算后 $id 的整行应与增量维护一致", before[id], db.playbackStateDao().get(id))
        }

        val a = repo.snapshot("a").first()
        assertEquals(2, a.playCount)
        assertEquals(1, a.completedCount)
        assertEquals(183_000L, a.listenedMsTotal)
        assertEquals(90_000L, repo.snapshot("b").first().listenedMsTotal)
    }

    /** 缓存被写坏（崩溃、手工改库、重新导入）时，重算把数字拉回真源 */
    @Test
    fun 重算能修正被写坏的聚合缓存() = runBlocking {
        repo.settleSession(session("a", listened = 60_000), 1.0f)
        db.playbackStateDao().delete("a")

        // 行没了 → ensureRow 补出全 0 的新行，增量路径的历史随之丢失
        repo.openSession("a", "第一首", 0, DURATION)
        val zeroed = repo.snapshot("a").first()
        assertEquals(0, zeroed.playCount)
        assertEquals(0L, zeroed.listenedMsTotal)

        repo.recomputeAggregates()
        val fixed = repo.snapshot("a").first()
        assertEquals(1, fixed.playCount)
        assertEquals(60_000L, fixed.listenedMsTotal)
        // 刚开的会话还是 ACTIVE 行：重算不该把它计进次数
        assertEquals(0, fixed.completedCount)
    }

    /** FR-405：上次进程留下的 ACTIVE 行按最后一次 flush 结算，恢复后聚合与真源一致 */
    @Test
    fun 崩溃恢复把活跃行结算为crashed() = runBlocking {
        // 模拟上一次进程：开场 + flush 后进程死亡（ended_at 仍为 0）
        repo.openSession("a", "第一首", 0, DURATION)
        clock.wall += 70_000
        clock.monotonic += 70_000
        repo.flushProgress(progress("a", position = 70_000, listened = 70_000, consumed = 70_000), 1.0f)
        val flushWall = clock.wall

        // 新进程：新仓库实例 + 时间又走了 5s——这 5s 是进程死亡期，不能算收听
        val revived = PlaybackRepositoryImpl(db, FakeClock().apply { wall = flushWall + 5_000 })
        assertEquals(1, revived.recoverCrashedSessions())

        assertEquals(emptyList<ListeningSessionEntity>(), db.listeningSessionDao().openSessions())
        val row = settled("a").single()
        assertEquals(ListeningSessionEntity.Status.CRASHED, row.sessionStatus)
        assertEquals(70_000L, row.listenedMs)
        assertTrue(row.isCounted)
        assertEquals(flushWall, row.endedAtMs)

        // 恢复不该把同一段再算一遍：聚合仍等于会话表
        val state = revived.snapshot("a").first()
        assertEquals(1, state.playCount)
        assertEquals(70_000L, state.listenedMsTotal)
        assertEquals(flushWall, state.lastPlayedAtMs)
    }

    /** 太短的残留行：结算后不占播放次数，但听过的时间还是要算 */
    @Test
    fun 崩溃恢复不给短会话计次() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 2_000, listened = 2_000, consumed = 2_000), 1.0f)

        val revived = PlaybackRepositoryImpl(db, clock)
        assertEquals(1, revived.recoverCrashedSessions())
        val state = revived.snapshot("a").first()
        assertEquals(0, state.playCount)
        assertEquals(2_000L, state.listenedMsTotal)
        assertFalse(settled("a").single().isCounted)
    }

    /** 恢复只做一次：第二次调用不能再把当前进程刚开的会话当成残留 */
    @Test
    fun 恢复幂等且不会结算之后的新会话() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 30_000, listened = 30_000, consumed = 30_000), 1.0f)

        val revived = PlaybackRepositoryImpl(db, clock)
        // 启动瞬间就点播放：恢复藏在第一次写库之前，所以新会话不会被误结算
        revived.openSession("b", "第二首", 0, DURATION)
        assertEquals(listOf("b"), db.listeningSessionDao().openSessions().map { it.audioId })
        assertEquals(ListeningSessionEntity.Status.CRASHED, settled("a").single().sessionStatus)
        assertEquals(0, revived.recoverCrashedSessions())
    }

    /** 进行中的会话不进统计页，否则「重算 == 增量」永远不平 */
    @Test
    fun 统计只算已结算的会话() = runBlocking {
        repo.settleSession(session("a", listened = 60_000), 1.0f)
        repo.openSession("b", "第二首", 0, DURATION)
        repo.flushProgress(progress("b", position = 50_000, listened = 50_000, consumed = 50_000), 1.0f)

        val stats = repo.stats().first()
        assertEquals(60_000L, stats.totalListenedMs)
        assertEquals(1, stats.totalPlayCount)
        assertEquals(0, stats.totalCompletedCount)
    }

    /** AGENTS.md §4：从书架删书不能删掉收听历史 */
    @Test
    fun 删书保留历史会话但清掉聚合缓存() = runBlocking {
        repo.settleSession(session("a", listened = 60_000), 1.0f)
        db.audioBookDao().delete("a")

        assertEquals(1, settled("a").size)
        assertNotNull(db.audioBookDao().getById("b"))
        assertNull(db.audioBookDao().getById("a"))
        // 聚合缓存随书 CASCADE 掉，重算只影响还在架上的书
        assertNull(db.playbackStateDao().get("a"))
        repo.recomputeAggregates()
        assertNull(db.playbackStateDao().get("a"))
    }

    @Test
    fun 最近收听列表按时间倒序并带标题快照() = runBlocking {
        repo.settleSession(session("a", listened = 10_000), 1.0f)
        clock.wall += 10_000
        repo.settleSession(session("b", listened = 20_000), 1.0f)

        val recent = repo.recentSessions(10).first()
        assertEquals(listOf("b", "a"), recent.map { it.audioId })
        assertEquals("标题b", recent[0].title)
        assertEquals(20_000L, recent[0].listenedMs)
        assertFalse(recent[0].completed)
    }

    /** limit 生效：统计页只取最近若干条 */
    @Test
    fun 最近收听列表受limit约束() = runBlocking {
        repo.settleSession(session("a", listened = 10_000), 1.0f)
        clock.wall += 1_000
        repo.settleSession(session("b", listened = 10_000), 1.0f)
        assertEquals(1, repo.recentSessions(1).first().size)
    }

    /** 倍速只放大消耗量、不放大收听时长，落库后两者各归各位 */
    @Test
    fun 倍速会话的两个时长各自落库() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 60_000, listened = 30_000, consumed = 60_000), 2.0f)
        repo.settleSession(session("a", listened = 30_000, consumed = 60_000, endPosition = 60_000), 2.0f)

        val row = settled("a").single()
        assertEquals(30_000L, row.listenedMs)
        assertEquals(60_000L, row.consumedAudioMs)
        assertEquals(30_000L, repo.snapshot("a").first().listenedMsTotal)
        assertEquals(2.0f, repo.snapshot("a").first().speed, 0.0001f)
    }

    /** 时长未知（还没读到 / 读取失败）不能把已知时长抹成 0 */
    @Test
    fun 未知时长不覆盖已知时长() = runBlocking {
        repo.openSession("a", "第一首", 0, DURATION)
        repo.flushProgress(progress("a", position = 10_000, listened = 10_000, consumed = 10_000).copy(durationMs = 0), 1.0f)

        assertEquals(DURATION, db.listeningSessionDao().openSessions().single().durationMsSnapshot)
        assertEquals(DURATION, db.playbackStateDao().get("a")!!.durationMs)
        // 位置照常推进
        assertEquals(10_000L, repo.snapshot("a").first().positionMs)
    }

    /** 反过来：开场时时长未知，后来读到了要写进去 */
    @Test
    fun 后读到的时长会补上() = runBlocking {
        repo.openSession("a", "第一首", 0, 0)
        repo.flushProgress(progress("a", position = 10_000, listened = 10_000, consumed = 10_000), 1.0f)
        assertEquals(DURATION, db.playbackStateDao().get("a")!!.durationMs)
        assertEquals(DURATION, db.listeningSessionDao().openSessions().single().durationMsSnapshot)
    }

    /**
     * FR-303 的「每首独立保存」：倍速是 `playback_state` 上的一列，改它不能顺手动进度、
     * 计数或会话表——否则「只是调了个倍速」会污染统计。
     */
    @Test
    fun 倍速单独保存不影响进度与计数() = runBlocking {
        repo.saveSpeed("a", 1.25f)
        repo.saveSpeed("b", 2.0f)

        val a = repo.snapshot("a").first()
        assertEquals(1.25f, a.speed, 0.0001f)
        assertEquals(0L, a.positionMs)
        assertEquals(0, a.playCount)
        assertEquals(0L, a.listenedMsTotal)
        assertEquals(2.0f, repo.snapshot("b").first().speed, 0.0001f)
        assertTrue(db.listeningSessionDao().openSessions().isEmpty())

        // 结算路径带着上一次的倍速回来时也不能把它冲掉
        repo.openSession("a", "第一首", 0, DURATION)
        repo.settleSession(session("a", listened = 20_000), 1.25f)
        assertEquals(1.25f, repo.snapshot("a").first().speed, 0.0001f)
    }

    private companion object {
        const val DURATION = 600_000L
    }
}
