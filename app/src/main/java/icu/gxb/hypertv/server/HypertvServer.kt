package icu.gxb.hypertv.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import icu.gxb.hypertv.BuildConfig
import icu.gxb.hypertv.data.repository.HypertvRepository
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内嵌 Ktor HTTP 服务器，绑定 0.0.0.0:SERVER_PORT。
 *
 * WebUI 静态页从 assets/webui 读取（Android 上比 classpath resources 更可靠）。
 */
@Singleton
class HypertvServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HypertvRepository,
) {
    private var engine: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (engine != null) return
        val newEngine = embeddedServer(
            factory = CIO,
            host = "0.0.0.0",
            port = SERVER_PORT,
            module = {
                hypertvModule(
                    version = BuildConfig.VERSION_NAME,
                    indexHtml = ::readIndexHtml,
                    playlistStore = HypertvPlaylistImportStore(repository),
                )
            },
        )
        newEngine.start(wait = false)
        engine = newEngine
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
        engine = null
    }

    /** 读取 assets 中的 WebUI 首页；缺失时返回 null，路由会退回内置占位文案。 */
    private fun readIndexHtml(): String? {
        return try {
            context.assets.open("webui/index.html").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
