package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.GroupEntity
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * HyperTV 内嵌 Web 服务路由。
 *
 * 独立成 Application 扩展函数是为了用 ktor-server-test-host 做 JVM 单测：
 * 版本号、IP 来源、WebUI 静态资源、导入与管理所需的数据入口/URL 拉取全部参数注入，
 * 不依赖 Android 环境。路由单测注入 fake 的 [PlaylistImportStore]、[ChannelManagementStore]
 * 与静态资源加载函数。
 *
 * 路由分层：
 * - `/api/...`：JSON API（status / 导入 / 管理）
 * - `/`、`/{path...}`：WebUI 静态资源（assets/webui 下任意文件）
 */
fun Application.hypertvModule(
    version: String,
    ipProvider: () -> String? = ::getLocalIpv4,
    webAssetLoader: (path: String) -> ByteArray? = { null },
    playlistStore: PlaylistImportStore,
    managementStore: ChannelManagementStore,
    urlFetcher: suspend (String) -> ByteArray = PlaylistUrlFetcher::fetch,
    /** 上传文件落盘，返回可读回路径（文件型源 refresh 依赖）；默认不落盘（仅测试/无本地文件系统场景） */
    saveFile: suspend (sourceId: String, bytes: ByteArray) -> String = { _, _ -> "" },
    /** 按落盘路径读回文件字节，供 refresh 文件型源使用 */
    readFile: suspend (path: String) -> ByteArray = { throw PlaylistImportException("文件读取未配置") },
    m3uParser: M3uParser = M3uParser(),
    encodingDetector: EncodingDetector = EncodingDetector,
) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }
    val importer = PlaylistImporter(m3uParser, encodingDetector, playlistStore, urlFetcher, saveFile, readFile)
    routing {
        get("/api/status") {
            call.respond(
                HttpStatusCode.OK,
                ServerStatus(version = version, ip = ipProvider(), port = SERVER_PORT),
            )
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
        post("/api/playlist/upload/preview") {
            val (fileName, sourceName, bytes) = try {
                call.receiveUploadFile()
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "接收上传文件失败"))
                return@post
            }
            try {
                if (bytes.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ImportError("文件内容为空"))
                } else {
                    call.respond(HttpStatusCode.OK, importer.previewBytes(fileName, bytes, sourceName))
                }
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "解析预览失败"))
            }
        }
        post("/api/playlist/upload") {
            val (fileName, sourceName, bytes) = try {
                call.receiveUploadFile()
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "接收上传文件失败"))
                return@post
            }
            try {
                if (bytes.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ImportError("文件内容为空"))
                } else {
                    call.respond(HttpStatusCode.OK, importer.importBytes(fileName, bytes, sourceName))
                }
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "导入失败"))
            }
        }

        // ---- 多源管理（ticket 08）----
        get("/api/playlists") {
            val sources = playlistStore.sources()
            val dtos = sources.map { source ->
                source.toPlaylistDto(channelCount = playlistStore.channelsBySource(source.id).size)
            }
            call.respond(HttpStatusCode.OK, dtos)
        }
        put("/api/playlists/{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少直播源 id"))
                return@put
            }
            val body = call.receiveBody<RenamePlaylistRequest>() ?: return@put
            val name = body.name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("名称不能为空"))
                return@put
            }
            val existing = playlistStore.sourceById(id)
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("直播源不存在"))
                return@put
            }
            val renamed = existing.copy(name = name)
            playlistStore.upsertSource(renamed)
            call.respond(HttpStatusCode.OK, renamed.toPlaylistDto(playlistStore.channelsBySource(id).size))
        }
        delete("/api/playlists/{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少直播源 id"))
                return@delete
            }
            if (playlistStore.sourceById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("直播源不存在"))
                return@delete
            }
            // 级联删除该源全部频道（含收藏记录），外键 CASCADE 保证（ADR-0004）
            playlistStore.deleteSource(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/playlists/{id}/refresh") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少直播源 id"))
                return@post
            }
            if (playlistStore.sourceById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("直播源不存在"))
                return@post
            }
            try {
                call.respond(HttpStatusCode.OK, importer.refreshSource(id))
            } catch (e: PlaylistImportException) {
                call.respond(HttpStatusCode.BadRequest, ImportError(e.message ?: "刷新失败"))
            }
        }

        // ---- 频道管理（ticket 07）----
        get("/api/channels") {
            val includeHidden = call.request.queryParameters["includeHidden"]?.toBooleanStrictOrNull() ?: false
            val channels = managementStore.channels()
            val visible = if (includeHidden) channels else channels.filter { !it.isHidden }
            call.respond(HttpStatusCode.OK, visible.map { it.toDto() })
        }
        get("/api/channels/favorites") {
            call.respond(HttpStatusCode.OK, managementStore.favoriteChannels().map { it.toDto() })
        }
        put("/api/channels/{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少频道 id"))
                return@put
            }
            val body = call.receiveBody<UpdateChannelRequest>() ?: return@put
            val existing = managementStore.channelById(id)
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("频道不存在"))
                return@put
            }
            val logo = when {
                body.logoUrl == null -> existing.logoUrl
                body.logoUrl.isBlank() -> null
                else -> body.logoUrl.trim()
            }
            val updated = existing.copy(
                name = body.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
                groupName = body.groupName?.trim() ?: existing.groupName,
                logoUrl = logo,
                isHidden = body.isHidden ?: existing.isHidden,
            )
            managementStore.updateChannel(updated)
            call.respond(HttpStatusCode.OK, updated.toDto())
        }
        delete("/api/channels/{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少频道 id"))
                return@delete
            }
            if (managementStore.channelById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("频道不存在"))
                return@delete
            }
            managementStore.deleteChannel(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/channels/reorder") {
            val body = call.receiveBody<ReorderChannelsRequest>() ?: return@post
            managementStore.reorderChannels(body.ids)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/channels/{id}/favorite") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少频道 id"))
                return@post
            }
            val body = call.receiveBody<FavoriteRequest>() ?: return@post
            if (managementStore.channelById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("频道不存在"))
                return@post
            }
            managementStore.setChannelFavorite(id, body.favorite)
            call.respond(HttpStatusCode.OK)
        }

        // ---- 分组管理（ticket 07）----
        get("/api/groups") {
            val groups = managementStore.groups()
            val counts = managementStore.channels().groupingBy { it.groupName }.eachCount()
            call.respond(HttpStatusCode.OK, groups.map { it.toDto(counts[it.name] ?: 0) })
        }
        post("/api/groups") {
            val body = call.receiveBody<GroupUpsertRequest>() ?: return@post
            val name = body.name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("分组名不能为空"))
                return@post
            }
            val groups = managementStore.groups()
            val newName = body.newName?.trim()
            if (newName.isNullOrEmpty()) {
                // 新建：追加到分组末尾
                if (groups.any { it.name == name }) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("分组已存在：$name"))
                    return@post
                }
                val orderIndex = (groups.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val group = GroupEntity(name = name, orderIndex = orderIndex, isCollapsed = false)
                managementStore.upsertGroup(group)
                call.respond(HttpStatusCode.OK, group.toDto(0))
            } else {
                // 重命名：沿用原排序位置，频道归入新名
                val existing = groups.firstOrNull { it.name == name }
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("分组不存在：$name"))
                    return@post
                }
                if (newName == name) {
                    call.respond(HttpStatusCode.OK, existing.toDto(countOfChannels(managementStore, name)))
                    return@post
                }
                if (groups.any { it.name == newName }) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("分组已存在：$newName"))
                    return@post
                }
                val renamed = existing.copy(name = newName)
                managementStore.upsertGroup(renamed)
                val ids = managementStore.channels().filter { it.groupName == name }.map { it.id }
                if (ids.isNotEmpty()) managementStore.moveChannelsToGroup(ids, newName)
                managementStore.deleteGroup(name)
                call.respond(HttpStatusCode.OK, renamed.toDto(ids.size))
            }
        }
        delete("/api/groups/{name}") {
            val name = call.parameters["name"]
            if (name.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少分组名"))
                return@delete
            }
            if (managementStore.groups().none { it.name == name }) {
                call.respond(HttpStatusCode.NotFound, ApiError("分组不存在：$name"))
                return@delete
            }
            managementStore.deleteGroup(name)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/groups/reorder") {
            val body = call.receiveBody<ReorderGroupsRequest>() ?: return@post
            managementStore.reorderGroups(body.names)
            call.respond(HttpStatusCode.OK)
        }

        // ---- WebUI 静态资源（先注册 /api，保证 API 优先匹配）----
        get("/") {
            call.serveAsset("index.html", webAssetLoader)
        }
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (path.startsWith("api/")) {
                call.respond(HttpStatusCode.NotFound, ApiError("接口不存在"))
            } else {
                call.serveAsset(path, webAssetLoader)
            }
        }
    }
}

private suspend fun ApplicationCall.serveAsset(rawPath: String, loader: (String) -> ByteArray?) {
    val normalized = normalizeAssetPath(rawPath)
    if (normalized == null) {
        respond(HttpStatusCode.BadRequest, ApiError("非法的资源路径"))
        return
    }
    val bytes = loader(normalized)
    if (bytes == null) {
        respond(HttpStatusCode.NotFound, ApiError("资源不存在：$normalized"))
    } else {
        respondBytes(bytes, contentTypeOf(normalized))
    }
}

/** 把客户端路径归一化为 assets/webui 下的相对路径；防路径穿越。 */
private fun normalizeAssetPath(raw: String): String? {
    val clean = raw.trim().removePrefix("/")
    if (clean.isEmpty()) return "index.html"
    if (clean.contains('\\') || clean.contains('\u0000')) return null
    val segments = clean.split('/')
    if (segments.any { it == ".." }) return null
    if (segments.any { it.isEmpty() }) return null
    return clean
}

private fun contentTypeOf(path: String): ContentType {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        "js" -> ContentType("application", "javascript")
        "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        "json" -> ContentType.Application.Json
        "svg" -> ContentType("image", "svg+xml")
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType("image", "webp")
        "ico" -> ContentType("image", "x-icon")
        "woff" -> ContentType("font", "woff")
        "woff2" -> ContentType("font", "woff2")
        "ttf" -> ContentType("font", "ttf")
        "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        else -> ContentType.Application.OctetStream
    }
}

private suspend fun countOfChannels(store: ChannelManagementStore, groupName: String): Int =
    store.channels().count { it.groupName == groupName }

/** 解析 JSON 请求体；格式错误时返回 400 并返回 null（调用方应就此返回）。 */
private suspend inline fun <reified T> ApplicationCall.receiveBody(): T? {
    return try {
        receive()
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ApiError("请求体格式错误：${e.message}"))
        null
    }
}

/** 上传文件 multipart 解析结果：文件名 + 可选的 sourceName 文本字段 + 文件字节。 */
private data class UploadedFile(
    val fileName: String?,
    val sourceName: String?,
    val bytes: ByteArray,
)

/** 从 multipart 中取第一个文件字段与可选的 sourceName 文本字段（读完全部 part）。 */
private suspend fun ApplicationCall.receiveUploadFile(): UploadedFile {
    val multipart = receiveMultipart()
    var fileName: String? = null
    var sourceName: String? = null
    var bytes: ByteArray? = null
    while (true) {
        val part = multipart.readPart() ?: break
        if (part is PartData.FileItem && bytes == null) {
            fileName = part.originalFileName
            bytes = part.provider().readRemaining().readByteArray()
        } else if (part is PartData.FormItem && part.name == "sourceName") {
            sourceName = part.value.trim().takeIf { it.isNotEmpty() }
        }
        part.dispose()
    }
    return UploadedFile(fileName, sourceName, bytes ?: ByteArray(0))
}
