package com.xinchen.echomind.core.conf

/**
 * 播放与统计的唯一阈值来源（docs/playback-design.md §7）。
 * 改这里 = 改统计口径，必须同步：SessionTracker 实现、单测、统计页文案、文档（AGENTS.md §4）。
 */
object PlaybackPolicy {
    /** 累计实际播放达到该墙上时长才算一次有效收听（FR-401） */
    const val MIN_VALID_SESSION_LISTENED_MS: Long = 5_000

    /** 完播所需的「真实听过的音频推进量」比例（FR-406，防拖到结尾算听完） */
    const val COMPLETED_CONSUMED_RATIO: Float = 0.90f

    /** 距末尾多少毫秒内视为「到达末尾」；配合上一条得到约 95% 完成率（FR-407） */
    const val END_TOLERANCE_MS: Long = 2_000

    /** 进度写库节流（FR-412） */
    const val POSITION_FLUSH_INTERVAL_MS: Long = 10_000

    /**
     * 转录进度写库节流（FR-507）。比播放位置的 10 s 密：转录进度条是用户盯着看的，
     * 10 s 才跳一下会被当成卡住；2 s 仍满足「≥1s」的写库纪律（AGENTS.md §4），
     * 一本 34 分钟的书整轮也只写几百行。
     */
    const val TRANSCRIPT_PROGRESS_INTERVAL_MS: Long = 2_000

    /**
     * 两次位置上报之间允许计入 `consumed_audio_ms` 的最大正向步长。
     * tick 是 500ms，倍速 2.0 时一步最多 ~1000ms 音频；取 2000ms 留一倍余量。
     * 超过它的位置变化一律当作跳转（通知栏拖条、Media3 插值），不算「真听过」（FR-406）。
     */
    const val MAX_COUNTED_POSITION_STEP_MS: Long = 2_000

    /** UI 进度与字幕 tick */
    const val UI_TICK_INTERVAL_MS: Long = 500

    /** 落在两句间隙时，越过下一句起点前多少毫秒不切换当前句（FR-704） */
    const val GAP_ENTER_MARGIN_MS: Long = 250

    const val SKIP_STEP_MS: Long = 15_000

    /** 字幕窗口的默认半径（当前句前后各几句）：七句窗口（FR-701）；播放页会按可用高度在 [SUBTITLE_WINDOW_MIN_RADIUS] 与更大值之间自适应 */
    const val SUBTITLE_WINDOW_RADIUS: Int = 3

    /**
     * 字幕窗口半径的下限（FR-701 注记，2026-10-24）：手机屏放不下七句长句时允许收到「当前句 ± 1」，
     * 大屏则按高度放大——窗口大小由页面量出来的空间决定，不再是固定七句。
     */
    const val SUBTITLE_WINDOW_MIN_RADIUS: Int = 1

    val SPEED_STEPS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    const val DEFAULT_SPEED: Float = 1.0f
}
