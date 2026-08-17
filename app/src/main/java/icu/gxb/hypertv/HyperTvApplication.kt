package icu.gxb.hypertv

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import icu.gxb.hypertv.service.ServerService

@HiltAndroidApp
class HyperTvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // App 进程启动即拉起内嵌服务（前台服务，START_STICKY 保证被杀后自恢复）
        startServerService()
    }

    private fun startServerService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, ServerService::class.java))
        } catch (e: Exception) {
            // 后台进程启动（如系统广播）且 12+ 限制下可能被拒，静默降级，待 Activity 前台时再拉起
            Log.w(TAG, "Failed to start ServerService", e)
        }
    }

    private companion object {
        const val TAG = "HyperTvApplication"
    }
}
