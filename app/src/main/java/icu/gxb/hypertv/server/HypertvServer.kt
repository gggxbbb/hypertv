package icu.gxb.hypertv.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import icu.gxb.hypertv.BuildConfig
import icu.gxb.hypertv.data.repository.HypertvRepository
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.io.File
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
                    webAssetLoader = ::readWebAsset,
                    playlistStore = HypertvPlaylistImportStore(repository),
                    managementStore = HypertvChannelManagementStore(repository),
                    saveFile = ::saveUploadedFile,
                    readFile = ::readUploadedFile,
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
}
