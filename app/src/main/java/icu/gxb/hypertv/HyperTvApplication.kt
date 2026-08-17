package icu.gxb.hypertv

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import icu.gxb.hypertv.di.ApplicationScope
import icu.gxb.hypertv.epg.EpgRefreshService
import icu.gxb.hypertv.player.PlayerController
import icu.gxb.hypertv.service.ServerService
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class HyperTvApplication : Application() {

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var epgRefresher: EpgRefreshService

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // App 进程启动即创建并准备播放器：观察频道列表，触发开机自动播放
        //（冷启动到播放 <3s 的关键：尽量早地 prepare 上次频道）
        playerController.start()
        // App 进程启动即拉起内嵌服务（前台服务，START_STICKY 保证被杀后自恢复）
        startServerService()
        // EPG 启动过期即刷（ADR-0005）：距上次成功刷新 >12h 则后台自动拉取，不阻塞启动
        applicationScope.launch { epgRefresher.refreshIfStale() }
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
