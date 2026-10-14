package com.xinchen.echomind.domain.model

/** 会话结束状态（listening_session.session_status），口径见 docs/playback-design.md §3.3 */
enum class SessionStatus { Active, Finished, Interrupted, SkippedTooShort, Crashed }

/** 为什么结束：决定落到哪个 status，不直接暴露 status 给调用方，避免两处映射口径漂移 */
enum class SessionEndReason {
    /** 播放头自己走到末尾 */
    Natural,

    /** 用户主动停止/退出播放页 */
    UserStopped,

    /** 切歌：上一首要先结算（FR-409） */
    SwitchedAudio,

    /** 音频焦点丢失（FR-305） */
    FocusLost,

    /** 拔出耳机（FR-306） */
    BecomingNoisy,

    /** 启动时发现的 ACTIVE 残留行，按最后一次 flush 的数值结算（FR-405） */
    ProcessRestart,
}

/**
 * 一次收听会话（listening_session 一行）。
 * `listenedMs` 是墙上播放时间，`consumedAudioMs` 是音频位置正向推进量——两者口径不同，不得混用。
 */
data class ListeningSessionData(
    val audioId: String,
    val audioTitleSnapshot: String,
    val durationMsSnapshot: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val listenedMs: Long,
    val consumedAudioMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val maxReachedPositionMs: Long,
    val completed: Boolean,
    val counted: Boolean,
    val status: SessionStatus,
)

/** 进度落盘快照（FR-412 的节流写库与 FR-309 的位置恢复都用它） */
data class PlaybackProgress(
    val audioId: String,
    val positionMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val consumedAudioMs: Long,
    val maxReachedPositionMs: Long,
)
