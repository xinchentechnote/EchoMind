package com.xinchen.echomind.ui.common

/** 时长展示：0 或负数视为「时长未知」（FR-104 的读取失败情形） */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "时长未知"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 收听时长的中文口语化：累计值用它，单首时长用 formatDuration */
fun formatListenDuration(ms: Long): String {
    if (ms <= 0) return "0 分钟"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h} 小时 ${m} 分钟" else "${m} 分钟"
}

fun formatRelativeTime(epochMs: Long, nowEpochMs: Long): String {
    if (epochMs <= 0) return "未播放"
    val diff = (nowEpochMs - epochMs).coerceAtLeast(0)
    val min = diff / 60_000
    val hour = min / 60
    val day = hour / 24
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        hour < 24 -> "${hour} 小时前"
        day < 30 -> "${day} 天前"
        day < 365 -> "${day / 30} 个月前"
        else -> "${day / 365} 年前"
    }
}

/** 进度百分比，时长未知时返回 null（UI 不画假进度条） */
fun progressFraction(positionMs: Long, durationMs: Long): Float? {
    if (durationMs <= 0) return null
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
