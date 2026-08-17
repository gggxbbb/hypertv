package icu.gxb.hypertv.server

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import icu.gxb.hypertv.BuildConfig
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.di.ApplicationScope
import icu.gxb.hypertv.epg.EpgRefreshService
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 内嵌 Ktor HTTP 服务器，绑定 0.0.0.0:动态端口。
 *
 * 端口策略（动态端口改造）：由 [ServerPortManager] 决定——
 * 优先复用 app_config 已保存端口，绑定失败则在 49152-65535 随机换端口重试，
 * listen 成功即持久化；全部失败走降级路径（[engine] 保持 null，服务照常运行）。
 *
 * [start] 异步执行（端口获取涉及 Room 读写，需协程）：实际端口通过 [port] StateFlow
 * 暴露给 UI 与通知（null = 未启动/启动失败，有值 = 当前实际监听端口）。
 *
 * WebUI 静态页从 assets/webui 读取（Android 上比 classpath resources 更可靠）。
 * ticket 11 错误处理边界复核：启动失败不崩溃，前台服务照常运行，
 * 引导页/关于页显示"服务未启动"而非错误的固定端口。
 */
@Singleton
class HypertvServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HypertvRepository,
    private val epgRefresher: EpgRefreshService,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private var startJob: Job? = null

    private val portManager = ServerPortManager(RepositoryPortStore(repository))

    private val _port = MutableStateFlow<Int?>(null)

    /** 当前实际监听端口；服务未启动/启动失败时为 null（UI/通知/status 读取）。 */
    val port: StateFlow<Int?> = _port.asStateFlow()

    @Synchronized
    fun start() {
        if (engine != null || startJob?.isActive == true) return
        startJob = applicationScope.launch {
            try {
                acquireAndStart()
            } catch (e: Exception) {
                // 兜底：任何意外异常都不崩溃（沿用 ticket 11 降级路径）
                Log.e(TAG, "内嵌服务启动失败", e)
            } finally {
                startJob = null
            }
        }
    }

    private suspend fun acquireAndStart() {
        val chosen = portManager.acquirePort { candidate -> bindOn(candidate) }
        if (chosen == null) {
            // 全部候选端口均不可用：WebUI 暂不可用，但 App 不崩溃
            Log.e(TAG, "内嵌服务启动失败：全部候选端口（49152-65535）均绑定失败")
        }
    }

    /** 在指定端口显式绑定；成功记录 engine 并更新 [port]，失败返回 false 交由端口管理器重试。 */
    private fun bindOn(candidate: Int): Boolean {
        return try {
            val newEngine = embeddedServer(
                factory = CIO,
                host = "0.0.0.0",
                port = candidate,
                module = {
                    hypertvModule(
                        version = BuildConfig.VERSION_NAME,
                        portProvider = { port.value },
                        webAssetLoader = ::readWebAsset,
                        playlistStore = HypertvPlaylistImportStore(repository),
                        managementStore = HypertvChannelManagementStore(repository),
                        saveFile = ::saveUploadedFile,
                        readFile = ::readUploadedFile,
                        epgStore = HypertvEpgStore(repository),
                        epgRefresher = epgRefresher,
                        epgScope = applicationScope,
                    )
                },
            )
            newEngine.start(wait = false)
            engine = newEngine
            _port.value = candidate
            true
        } catch (e: Exception) {
            // 端口占用/绑定失败：换端口重试（端口管理器驱动）
            Log.w(TAG, "端口 $candidate 绑定失败，尝试其他端口", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        startJob = null
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
        engine = null
        _port.value = null
    }

    /** 读取 assets/webui 下任意文件；缺失时返回 null，路由会退回 404。 */
    private fun readWebAsset(path: String): ByteArray? {
        return try {
            context.assets.open("webui/$path").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** 上传的 M3U 文件落盘到应用内部目录，返回绝对路径（文件型源 refresh 读回）。 */
    private fun saveUploadedFile(sourceId: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "playlist_uploads").apply { mkdirs() }
        return File(dir, "$sourceId.m3u").also { it.writeBytes(bytes) }.absolutePath
    }

    /** 按落盘路径读回上传文件内容；文件不存在时抛出 [PlaylistImportException]（路由转 400）。 */
    private fun readUploadedFile(path: String): ByteArray {
        val file = File(path)
        if (!file.exists() || !file.isFile) throw PlaylistImportException("源文件不存在：$path")
        return file.readBytes()
    }

    private companion object {
        const val TAG = "HypertvServer"
    }
}
