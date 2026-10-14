package com.xinchen.echomind.ui.nav

/**
 * 目的地清单。Phase 3~7 逐个把 placeholder 换成真实页面，路由名保持稳定。
 */
object Destinations {
    const val SHELF = "shelf"
    const val PLAYER = "player/{audioId}"
    const val TRANSCRIPT = "transcript"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    fun player(audioId: String) = "player/$audioId"
}
