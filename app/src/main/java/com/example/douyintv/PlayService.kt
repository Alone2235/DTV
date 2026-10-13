package com.example.douyintv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台播放服务：提升进程优先级（对齐 B 站 TV 的存活策略）。
 * API 17（4.2）startForeground 可用（无需通知渠道，通知栏显示一条播放状态）。
 * ★ targetSdk 26：API 26+ 必须建通知渠道，否则通知不显示、前台服务形同虚设；
 *   且 manifest 已补 FOREGROUND_SERVICE 权限（否则 startForegroundService 抛 SecurityException）。
 */
class PlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val noti = buildNotification()
            // API 17 支持两参 startForeground；通知图标用应用图标兜底
            startForeground(1, noti)
        } catch (_: Throwable) {}
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), 0)
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW)
                    )
                } catch (_: Throwable) {}
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }
        builder.setSmallIcon(android.R.drawable.ic_media_play)
        builder.setContentTitle("抖音TV")
        builder.setContentText("正在播放")
        builder.setContentIntent(pi)
        builder.setOngoing(true)
        return if (Build.VERSION.SDK_INT >= 16) builder.build() else builder.notification
    }

    companion object {
        private const val CHANNEL_ID = "playback"
    }
}
