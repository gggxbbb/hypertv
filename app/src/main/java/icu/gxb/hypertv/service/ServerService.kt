package icu.gxb.hypertv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import icu.gxb.hypertv.R
import icu.gxb.hypertv.net.getLocalIpv4
import icu.gxb.hypertv.server.HypertvServer
import icu.gxb.hypertv.server.SERVER_PORT
import javax.inject.Inject

/**
 * 承载内嵌 HTTP 服务的前台服务：
 * - 常驻通知（Android 8+ 必须走通知渠道）
 * - START_STICKY：进程被杀后系统重启服务时自动恢复
 * - 重启（intent 为 null）时若服务未运行则重新拉起
 */
@AndroidEntryPoint
class ServerService : Service() {

    @Inject
    lateinit var server: HypertvServer

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())
        server.start()
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val ip = getLocalIpv4() ?: "无法获取 IP"
        val webuiUrl = "http://$ip:$SERVER_PORT"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text, webuiUrl))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.server_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "hypertv_server"
        const val NOTIFICATION_ID = 1
    }
}
