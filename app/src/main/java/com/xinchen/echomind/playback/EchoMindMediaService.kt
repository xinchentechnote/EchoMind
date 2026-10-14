package com.xinchen.echomind.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xinchen.echomind.MainActivity
import com.xinchen.echomind.R

/**
 * 后台播放服务（FR-304）。清单里以 `MediaSessionService` 的 action 暴露，
 * 因此系统设置、车机、手表这类 MediaBrowser 客户端将来能直接看到我们的队列。
 *
 * 服务用的是 [PlayerHolder] 里那个全 App 唯一的 ExoPlayer（同进程），
 * 所以通知栏的播放/暂停与页面上的按钮作用在同一个播放器上，不存在两套进度。
 */
class EchoMindMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 渠道自己建：Media3 只在需要时创建同名渠道，低重要性保证后台播放不震动不响铃
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PlayerHolder.CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PlayerHolder.CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .build()
        setMediaNotificationProvider(notificationProvider)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val session = MediaSession.Builder(this, PlayerHolder.get(this))
            .setSessionActivity(openApp)
            .build()
        mediaSession = session
        // 只赋值字段不够：通知由 MediaNotificationManager 盯着「已登记到服务」的会话，
        // 不调 addSession 就没有媒体通知，服务也不会进前台——实测后台播放会被 AudioHardening 静音。
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉 App：正在播就继续（后台播放是设计目标），没在播就停服务。
     * 播放器实例不释放——它属于进程而不是服务，划掉任务后进程可能还活着并被重新拉起。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlayerHolder.peek()
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            removeSession(this)
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
