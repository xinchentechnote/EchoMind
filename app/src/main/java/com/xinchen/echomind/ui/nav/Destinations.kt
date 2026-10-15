package com.xinchen.echomind.ui.nav

/**
 * 目的地清单。Phase 3~7 逐个把 placeholder 换成真实页面，路由名保持稳定。
 */
object Destinations {
    const val SHELF = "shelf"
    const val PLAYER = "player/{audioId}"
    const val TRANSCRIPT = "transcript"

    /** 转录页的第二条路由：从书架「转录这本书」带书过来。查询参数写成可选段，底部导航走 [TRANSCRIPT] */
    const val TRANSCRIPT_WITH_AUDIO = "transcript?audioId={audioId}"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    fun player(audioId: String) = "player/$audioId"

    fun transcript(audioId: String) = "transcript?audioId=$audioId"
}
