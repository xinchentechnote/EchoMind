package com.xinchen.echomind.data.repo

import android.content.Context

/**
 * 轻量运行设置（D42）：WMA 导入转码目标这类开关没有历史追溯需求，一行键值够了，
 * 刻意不入 Room（设置页 FR-902 的第一个落地项；「重算统计」「按文件夹授权」仍走各自的数据表）。
 *
 * 接口 + SharedPreferences 实现：设置页读写、导入编排读取都只依赖接口，JVM 测试用内存假件。
 */
interface AppSettings {

    /** WMA 导入时的转码目标：true = MP3（LAME 编码），false = M4A/AAC（默认，兼容性最好） */
    var wmaTranscodeToMp3: Boolean

    /** 单曲循环（FR-310）：播放页上切，跨书、跨进程记住；默认关 */
    var repeatOne: Boolean
}

class SharedPreferencesAppSettings(context: Context) : AppSettings {

    private val prefs = context.getSharedPreferences("echomind_settings", Context.MODE_PRIVATE)

    override var wmaTranscodeToMp3: Boolean
        get() = prefs.getBoolean(KEY_WMA_MP3, false)
        set(value) = prefs.edit().putBoolean(KEY_WMA_MP3, value).apply()

    override var repeatOne: Boolean
        get() = prefs.getBoolean(KEY_REPEAT_ONE, false)
        set(value) = prefs.edit().putBoolean(KEY_REPEAT_ONE, value).apply()

    private companion object {
        const val KEY_WMA_MP3 = "wma_transcode_to_mp3"
        const val KEY_REPEAT_ONE = "repeat_one"
    }
}
