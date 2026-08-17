package icu.gxb.hypertv.server

import icu.gxb.hypertv.m3u.EncodingDetector
import icu.gxb.hypertv.m3u.M3uParser
import icu.gxb.hypertv.net.getLocalIpv4
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * HyperTV 内嵌 Web 服务路由。
 *
 * 独立成 Application 扩展函数是为了用 ktor-server-test-host 做 JVM 单测：
 * 版本号、IP 来源、WebUI 首页、导入所需的解析器/数据入口/URL 拉取全部参数注入，
 * 不依赖 Android 环境。路由单测注入 fake 的 [PlaylistImportStore] 与拉取函数。
 */
fun Application.hypertvModule(
    version: String,
    ipProvider: () -> String? = ::getLocalIpv4,
    indexHtml: () -> String? = { null },
    playlistStore: PlaylistImportStore,
    urlFetcher: suspend (String) -> ByteArray = PlaylistUrlFetcher::fetch,
    m3uParser: M3uParser = M3uParser(),
    encodingDetector: EncodingDetector = EncodingDetector,
) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }
    val importer = PlaylistImporter(m3uParser, encodingDetector, playlistStore, urlFetcher)
    routing {
        get("/api/status") {
            call.respond(
                HttpStatusCode.OK,
                ServerStatus(version = version, ip = ipProvider(), port = SERVER_PORT),
            )
        }
        get("/") {
            val html = indexHtml()
            if (html.isNullOrBlank()) {
                call.respondText("HyperTV WebUI 占位页", ContentType.Text.Html)
            } else {
                call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }
        }
        // ---- 直播源导入（ticket 03）----
        post("/api/playlist/import/preview") {
            val request = call.receiveBody<ImportUrlRequest>() ?: return@post
            val url = request.url.trim()
            if (url.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ImportError("url 不能为空"))
                return@post
            }
            try {
                call.respond(HttpStatusCode.OK, importer.previewUrl(url))
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "解析预览失败"))
            }
        }
        post("/api/playlist/import") {
            val request = call.receiveBody<ImportUrlRequest>() ?: return@post
            val url = request.url.trim()
            if (url.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ImportError("url 不能为空"))
                return@post
            }
            try {
                call.respond(HttpStatusCode.OK, importer.importUrl(url))
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "导入失败"))
            }
        }
        post("/api/playlist/upload") {
            val (fileName, bytes) = try {
                call.receiveUploadFile()
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "接收上传文件失败"))
                return@post
            }
            try {
                if (bytes.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ImportError("文件内容为空"))
                } else {
                    call.respond(HttpStatusCode.OK, importer.importBytes(fileName, bytes))
                }
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "导入失败"))
            }
        }
    }
}

/** 解析 JSON 请求体；格式错误时返回 400 并返回 null（调用方应就此返回）。 */
private suspend inline fun <reified T> ApplicationCall.receiveBody(): T? {
    return try {
        receive()
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ImportError("请求体格式错误：${e.message}"))
        null
    }
}

/** 从 multipart 中取第一个文件字段，返回 (原始文件名, 字节)。 */
private suspend fun ApplicationCall.receiveUploadFile(): Pair<String?, ByteArray> {
    val multipart = receiveMultipart()
    var fileName: String? = null
    var bytes: ByteArray? = null
    while (bytes == null) {
        val part = multipart.readPart() ?: break
        if (part is PartData.FileItem) {
            fileName = part.originalFileName
            bytes = part.provider().readRemaining().readByteArray()
        }
        part.dispose()
    }
    return fileName to (bytes ?: ByteArray(0))
}
