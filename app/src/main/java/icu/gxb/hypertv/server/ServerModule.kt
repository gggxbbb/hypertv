package icu.gxb.hypertv.server

import icu.gxb.hypertv.net.getLocalIpv4
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * HyperTV 内嵌 Web 服务路由。
 *
 * 独立成 Application 扩展函数是为了用 ktor-server-test-host 做 JVM 单测：
 * 版本号、IP 来源、WebUI 首页内容全部由参数注入，不依赖 Android 环境。
 */
fun Application.hypertvModule(
    version: String,
    ipProvider: () -> String? = ::getLocalIpv4,
    indexHtml: () -> String? = { null },
) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }
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
    }
}
