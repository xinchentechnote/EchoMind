package com.xinchen.echomind.ui.common

import android.util.Log

/**
 * JVM 单测里 `android.util.Log` 未 mock，一调就抛 "not mocked"——而 `testOptions` 刻意不开
 * `isReturnDefaultValues`（见 `app/build.gradle.kts`，不靠开关掩盖对 Android 类的意外依赖）。
 * 日志是旁路，它的失败不能连累用户路径，所以吞掉异常：代价只是极端情况下少一条 logcat。
 */
fun safeLogW(tag: String, message: String, error: Throwable? = null) {
    try {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    } catch (_: Throwable) {
        // JVM 单测里走到这里；真机上不会
    }
}
