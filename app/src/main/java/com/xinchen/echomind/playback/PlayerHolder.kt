package com.xinchen.echomind.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.xinchen.echomind.core.conf.PlaybackPolicy

/**
 * 全 App 唯一的 ExoPlayer 实例（AGENTS.md §3）。
 *
 * Activity 与 `EchoMindMediaService` 共用它：通知栏控制、媒体键、字幕高亮作用在同一个播放器上，
 * 不会出现「前台一套进度、后台一套进度」的分裂状态。
 *
 * 播放器绑定在主线程 looper 上，所以**所有**方法只能在主线程调用（AGENTS 里的「UI 不持有播放器实例」
 * 指的是不自己构造，不是随便线程访问）。
 */
object PlayerHolder {

    /** 播放通知渠道（FR-304），Media3 的通知也发到这个渠道 */
    const val CHANNEL_ID = "echomind_playback"

    private var instance: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        instance?.let { return it }
        check(Looper.myLooper() != null) { "PlayerHolder.get() 必须在有 Looper 的线程（主线程）调用" }
        val player = ExoPlayer.Builder(context.applicationContext)
            .setLooper(Looper.getMainLooper())
            // USAGE_MEDIA + CONTENT_TYPE_SPEECH：听书内容走媒体音量通道，铃声通道不会被误用
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioBecomingNoisy = */ true,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 通知栏的 ±15s 用策略常量，UI 与通知不会是两套步长
            .setSeekBackIncrementMs(PlaybackPolicy.SKIP_STEP_MS)
            .setSeekForwardIncrementMs(PlaybackPolicy.SKIP_STEP_MS)
            .build()
        instance = player
        return player
    }

    /**
     * 服务可能被系统回收而进程还在，所以**没有** release：这个实例的生命周期等于进程。
     * 播放器自带资源回收（进程结束时 OS 收走），显式 release 反而会让重新拉起的 Activity
     * 拿到一个已释放的播放器。
     */
    fun peek(): ExoPlayer? = instance
}
