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
import icu.gxb.hypertv.di.ApplicationScope
import icu.gxb.hypertv.net.getLocalIpv4
import icu.gxb.hypertv.server.HypertvServer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 承载内嵌 HTTP 服务的前台服务：
 * - 常驻通知（Android 8+ 必须走通知渠道）
 * - START_STICKY：进程被杀后系统重启服务时自动恢复
 * - 重启（intent 为 null）时若服务未运行则重新拉起
 *
 * 动态端口改造：实际端口由 [HypertvServer.port] StateFlow 提供（null = 启动中/失败），
 * 通知随端口确定后刷新为含真实 WebUI 地址的文案，避免显示错误的固定端口。
 */
@AndroidEntryPoint
class ServerService : Service() {

    @Inject
    lateinit var server: HypertvServer

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(port = null))
        server.start()
        observePortForNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 端口确定后刷新前台通知（startForeground 重复调用即更新内容）。 */
    private fun observePortForNotification() {
        if (notificationJob?.isActive == true) return
        notificationJob = applicationScope.launch {
            server.port.collect { port ->
                startForegroundCompat(buildNotification(port))
            }
        }
    }

    private fun buildNotification(port: Int?): Notification {
        val ip = getLocalIpv4()
        val webuiUrl = if (ip != null && port != null) "http://$ip:$port" else null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(
                if (webuiUrl != null) {
                    getString(R.string.server_notification_text, webuiUrl)
                } else {
                    getString(R.string.server_notification_starting)
                },
            )
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
