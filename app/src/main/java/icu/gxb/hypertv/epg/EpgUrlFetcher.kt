package icu.gxb.hypertv.epg

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes

/**
 * 拉取 XMLTV 源的默认实现：Ktor HttpClient(CIO)，超时 30s。
 * 刷新服务单测注入 fake 拉取函数，不依赖本实现。
 */
object EpgUrlFetcher {

    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    suspend fun fetch(url: String): ByteArray {
        val response = try {
            client.get(url)
        } catch (e: Exception) {
            throw EpgException("拉取 EPG 源失败：${e.message}")
        }
        if (response.status.value !in 200..299) {
            throw EpgException("拉取 EPG 源失败：HTTP ${response.status.value}")
        }
        return response.bodyAsBytes()
    }
}
