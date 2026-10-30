package com.xinchen.echomind.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Compose 树里拿到的 [Context] 一路解包找宿主 [Activity]。
 *
 * 单 Activity 架构下 `LocalContext.current` 通常就是 MainActivity，但 Compose 会包一层
 * `ContextThemeWrapper`，直接 cast 会炸；播放页的屏幕常亮（FR-311）需要它的 window 来挂 flag。
 * 找不到（理论到不了）返回 null，调用方按「不常亮」处理。
 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
