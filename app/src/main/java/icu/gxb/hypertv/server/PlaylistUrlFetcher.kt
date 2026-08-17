package icu.gxb.hypertv.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.net.URI

/** URL 拉取失败（HTTP 非 2xx、超时、网络错误）时抛出，路由层转为 400。 */
class PlaylistImportException(message: String) : Exception(message)

/**
 * 拉取 M3U 源的默认实现：Ktor HttpClient(CIO)，超时 10s。
 * 路由单测注入 fake 拉取函数，不依赖本实现。
 */
object PlaylistUrlFetcher {

    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    suspend fun fetch(url: String): ByteArray {
        val response = try {
            client.get(url)
        } catch (e: Exception) {
            throw PlaylistImportException("拉取失败：${e.message}")
        }
        if (response.status.value !in 200..299) {
            throw PlaylistImportException("拉取失败：HTTP ${response.status.value}")
        }
        return response.bodyAsBytes()
    }
}

/** 直播源默认名称：URL 主机名；无主机（如本地文件路径）时退回整个 URL。 */
fun sourceNameFromUrl(url: String): String {
    return try {
        URI(url).host?.takeIf { it.isNotBlank() } ?: url
    } catch (_: Exception) {
        url
    }
}
