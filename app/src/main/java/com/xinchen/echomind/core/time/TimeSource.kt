package com.xinchen.echomind.core.time

/**
 * 时钟抽象。时长计算一律走 [monotonicMs]（不会因改系统时间/时区而跳变），
 * 墙钟只用于「什么时候」的展示与排序。测试里注入假时钟（AGENTS.md §4）。
 */
interface TimeSource {
    /** 单调递增毫秒（对应 SystemClock.elapsedRealtime） */
    fun monotonicMs(): Long

    /** 墙钟毫秒（对应 System.currentTimeMillis） */
    fun wallMs(): Long
}
