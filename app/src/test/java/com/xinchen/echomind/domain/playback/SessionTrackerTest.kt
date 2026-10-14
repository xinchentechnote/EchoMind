package com.xinchen.echomind.domain.playback

import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.FakeTimeSource
import com.xinchen.echomind.domain.model.SessionEndReason
import com.xinchen.echomind.domain.model.SessionStatus
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计口径的全套假时钟单测（FR-401~409）。这里通过就代表口径实现正确，
 * 不依赖任何 Android/Media3 类型——这正是 domain 层禁止 import android.* 换来的能力。
 */
class SessionTrackerTest {

    private val clock = FakeTimeSource()
    private val tracker = SessionTracker(clock)
    private var position = 0L

    private fun start(durationMs: Long = DURATION, audioId: String = "a", from: Long = position) {
        position = from
        tracker.start(audioId, "标题$audioId", from, durationMs)
        tracker.onPlayStarted()
    }

    /** 按 UI tick 步长推进：同时动单调钟与音频位置，模拟「真的在播」 */
    private fun play(wallMs: Long, speed: Float = 1f) {
        var remaining = wallMs
        while (remaining > 0) {
            val step = minOf(PlaybackPolicy.UI_TICK_INTERVAL_MS, remaining)
            position += (step * speed).toLong()
            clock.advanceMonotonic(step)
            tracker.onPosition(position)
            remaining -= step
        }
    }

    /** 播放器不在播的时间流逝（暂停/缓冲/焦点丢失） */
    private fun idle(wallMs: Long) = clock.advanceMonotonic(wallMs)

    @Test
    fun `误触 4_9 秒不算一次有效收听`() {
        start()
        play(4_900)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(4_900L, session.listenedMs)
        assertFalse(session.counted)
        assertEquals(SessionStatus.SkippedTooShort, session.status)
    }

    /** FR-401 的边界：刚好达到阈值即算一次 */
    @Test
    fun `刚好 5 秒算一次有效收听`() {
        start()
        play(5_000)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(5_000L, session.listenedMs)
        assertTrue(session.counted)
        assertEquals(SessionStatus.Finished, session.status)
    }

    /** FR-402：暂停/缓冲/焦点丢失期间一律不计时（6s 播放 + 20s 空转） */
    @Test
    fun `暂停与缓冲期间不计时`() {
        start()
        play(3_000)
        tracker.onPlayStopped()
        idle(20_000)
        tracker.onPlayStarted()
        play(3_000)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(6_000L, session.listenedMs)
        assertTrue(session.counted)
    }

    /** Media3 会在 isPlaying 与 STATE_READY 各回调一次，重复开始不能把时段算两遍 */
    @Test
    fun `重复的播放开始回调不重复计时`() {
        start()
        tracker.onPlayStarted()
        tracker.onPlayStarted()
        play(5_000)
        assertEquals(5_000L, tracker.endSession(SessionEndReason.UserStopped)!!.listenedMs)
    }

    /** 只 start 没真播起来（焦点被抢、秒退）：0 时长、不计数 */
    @Test
    fun `从未进入播放状态的会话不计次`() {
        tracker.start("a", "标题", 0, DURATION)
        val session = tracker.endSession(SessionEndReason.FocusLost)!!
        assertEquals(0L, session.listenedMs)
        assertFalse(session.counted)
        assertEquals(SessionStatus.SkippedTooShort, session.status)
    }

    /** FR-404：2x 播 10 分钟墙上时间 → listened=600000，consumed=1200000 */
    @Test
    fun `倍速播放的收听时长按墙上时间算`() {
        start(durationMs = 1_200_000)
        play(600_000, speed = 2f)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(600_000L, session.listenedMs)
        assertEquals(1_200_000L, session.consumedAudioMs)
    }

    /** FR-403：时长只来自单调钟，墙钟跳变（改系统时间/跨时区）不得影响任何统计 */
    @Test
    fun `墙钟跳变不影响时长`() {
        start()
        play(5_000)
        clock.jumpWall(86_400_000)
        play(1_000)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(6_000L, session.listenedMs)
        assertTrue(session.counted)
        // 墙钟差是一整天，单调钟差只有 6s：若时长用墙钟算就会得出 24 小时收听
        assertEquals(86_400_000L, session.endedAtMs - session.startedAtMs)
    }

    /** FR-406 反例：拖到结尾再停 → 位置到达末尾但真实推进量不足，不完播 */
    @Test
    fun `拖到结尾不算完播`() {
        start()
        play(10_000)
        tracker.onPlayStopped()
        position = DURATION
        tracker.onSeek(position)
        tracker.onPosition(position)
        val session = tracker.endSession(SessionEndReason.Natural)!!
        assertEquals(DURATION, session.maxReachedPositionMs)
        assertEquals(10_000L, session.consumedAudioMs)
        assertFalse(session.completed)
    }

    /** FR-406 正例：1.5x 把 10 分钟整首听完（墙上 6m40s）→ 完播 */
    @Test
    fun `倍速听完仍然算完播`() {
        start()
        play(400_000, speed = 1.5f)
        val session = tracker.endSession(SessionEndReason.Natural)!!
        assertEquals(DURATION, session.consumedAudioMs)
        assertTrue(session.completed)
        assertEquals(SessionStatus.Finished, session.status)
    }

    /** FR-408：同一首第二次完整收听仍然完播（次数由 DAO 累加） */
    @Test
    fun `两次完整收听各计一次完播`() {
        start()
        play(DURATION)
        val first = tracker.endSession(SessionEndReason.Natural)!!
        start(from = 0)
        play(DURATION)
        val second = tracker.endSession(SessionEndReason.Natural)!!
        assertTrue(first.completed)
        assertTrue(second.completed)
    }

    /** FR-407：末尾容差之外 31s 停住不完播，落在容差内（1.5s 之遥）才算 */
    @Test
    fun `没进入末尾容差不完播`() {
        start()
        play(DURATION - 31_000)
        assertFalse(tracker.endSession(SessionEndReason.UserStopped)!!.completed)

        start(from = 0)
        play(DURATION - PlaybackPolicy.END_TOLERANCE_MS + 500)
        assertTrue(tracker.endSession(SessionEndReason.Natural)!!.completed)
    }

    /** FR-406：外部跳转（通知栏拖条、Media3 插值突跳）的推进量不计入真实收听 */
    @Test
    fun `超过步长上限的位置跳变不计入收听量`() {
        start()
        play(5_000)
        position = 300_000
        tracker.onPosition(position)
        val progress = tracker.progress()!!
        assertEquals(5_000L, progress.consumedAudioMs)
        assertEquals(300_000L, progress.maxReachedPositionMs)
    }

    /** FR-409：切歌时上一首必须先结算，且落到 INTERRUPTED */
    @Test
    fun `切歌时自动结算上一首`() {
        start(audioId = "a")
        play(8_000)
        val carried = tracker.start("b", "标题b", 0, DURATION).also { tracker.onPlayStarted() }
        assertEquals("b", tracker.activeAudioId)
        assertEquals("a", carried!!.audioId)
        assertEquals(8_000L, carried.listenedMs)
        assertEquals(SessionStatus.Interrupted, carried.status)
    }

    /** 标题/时长快照必须进会话行：书后来被改名或删除，历史统计仍要能自解释 */
    @Test
    fun `会话保留标题与时长快照`() {
        start(durationMs = 123_456)
        play(6_000)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals("标题a", session.audioTitleSnapshot)
        assertEquals(123_456L, session.durationMsSnapshot)
    }

    /** 位置恢复与「听到哪儿了」要区分当前位置和到过的位置 */
    @Test
    fun `结束位置与最远位置分别记录`() {
        start()
        play(20_000)
        tracker.onPlayStopped()
        position = 5_000
        tracker.onSeek(position)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertEquals(0L, session.startPositionMs)
        assertEquals(5_000L, session.endPositionMs)
        assertEquals(20_000L, session.maxReachedPositionMs)
    }

    /** 时长未知（Media3 还没拿到 metadata）时只记进度，绝不判完播 */
    @Test
    fun `时长未知不判完播`() {
        start(durationMs = 0)
        play(60_000)
        val session = tracker.endSession(SessionEndReason.UserStopped)!!
        assertTrue(session.counted)
        assertFalse(session.completed)
    }

    /** flush 用：正在播但还没停的那一段也必须算进已听时长 */
    @Test
    fun `progress 包含进行中的播放段`() {
        start()
        play(7_000)
        val progress = tracker.progress()!!
        assertEquals(7_000L, progress.listenedMs)
        assertEquals(7_000L, progress.positionMs)
        assertEquals("a", progress.audioId)
    }

    /** 结算前 reachedEnd 只看播放头**当前**位置：拖到末尾停在那儿不等于播完 */
    @Test
    fun `末尾判定用当前位置而非最远位置`() {
        start()
        play(10_000)
        tracker.onSeek(DURATION)
        assertTrue(tracker.reachedEnd())
        tracker.onSeek(1_000)
        assertFalse(tracker.reachedEnd())
    }

    /** 导入时时长读取失败（duration=0）也要能完播：播放器报出真实时长后补进会话（FR-406 的分母） */
    @Test
    fun `播放器后续报出的时长会补进会话`() {
        start(durationMs = 0)
        play(10_000)
        assertFalse(tracker.progress()!!.durationMs > 0)
        tracker.onDuration(DURATION)
        play(DURATION - 10_000)
        val session = tracker.endSession(SessionEndReason.Natural)!!
        assertEquals(DURATION, session.durationMsSnapshot)
        assertTrue(session.completed)
    }

    @Test
    fun `未知时长不会覆盖已知时长`() {
        start(durationMs = DURATION)
        tracker.onDuration(0)
        assertEquals(DURATION, tracker.progress()!!.durationMs)
    }

    @Test
    fun `没有会话时所有读取都返回空`() {
        assertNull(tracker.activeAudioId)
        assertNull(tracker.progress())
        assertFalse(tracker.reachedEnd())
        assertNull(tracker.endSession(SessionEndReason.UserStopped))
        play(5_000)
        assertEquals(0L, tracker.listenedMs())
    }

    /** FR-407：改阈值常量，结论随之变化（口径唯一来源是 PlaybackPolicy，不许写死数字） */
    @Test
    fun `完播判定跟随策略常量`() {
        // 用 ceil 而不是直接乘：0.9f 在二进制里略小于 0.9，截断会把边界值判到阈值外
        val needed = ceil(DURATION * PlaybackPolicy.COMPLETED_CONSUMED_RATIO).toLong()
        assertFalse(SessionTracker.isCompleted(DURATION, DURATION, needed - 1))
        assertTrue(SessionTracker.isCompleted(DURATION, DURATION, needed))
        assertFalse(
            SessionTracker.isCompleted(DURATION, DURATION - PlaybackPolicy.END_TOLERANCE_MS - 1, DURATION)
        )
        assertTrue(
            SessionTracker.isCompleted(DURATION, DURATION - PlaybackPolicy.END_TOLERANCE_MS, DURATION)
        )
    }

    private companion object {
        const val DURATION = 600_000L
    }
}
