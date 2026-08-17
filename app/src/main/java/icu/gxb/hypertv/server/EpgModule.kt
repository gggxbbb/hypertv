package icu.gxb.hypertv.server

import icu.gxb.hypertv.epg.EpgRefreshService
import icu.gxb.hypertv.epg.EpgStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * EPG API 路由（ticket 09）：
 * - PUT /api/epg/source   设置全局/分组级 EPG 源（空 url = 清除）
 * - GET /api/epg/source   当前配置 + 刷新状态
 * - POST /api/epg/refresh 202 异步触发刷新（可查状态）
 * - GET /api/epg/now      Map<channelId, 当前节目>
 * - GET /api/epg/guide    某频道某天的节目表
 *
 * 独立成扩展函数以便单测直接挂载；必须在 WebUI catch-all 路由之前注册。
 */
fun Application.epgModule(
    epgStore: EpgStore,
    epgRefresher: EpgRefreshService,
    epgScope: CoroutineScope,
) {
    // 通常由 hypertvModule 已安装；单独挂载本模块时兜底（幂等守卫避免重复 install）
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true })
        }
    }
    routing {
        // ---- EPG 源配置 ----
        get("/api/epg/source") {
            call.respond(HttpStatusCode.OK, buildSourceConfig(epgStore, epgRefresher))
        }
        put("/api/epg/source") {
            val body = call.receiveBodyOrNull<EpgSourceRequest>() ?: return@put
            val url = body.url.trim()
            val groupId = body.groupId?.trim()?.takeIf { it.isNotEmpty() }
            if (groupId != null) {
                if (epgStore.groupByName(groupId) == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("分组不存在：$groupId"))
                    return@put
                }
                // 空 url = 清除分组覆盖（回退全局源）
                epgStore.updateGroupEpgUrl(groupId, url.takeIf { it.isNotEmpty() })
            } else {
                epgStore.setGlobalSourceUrl(url)
            }
            call.respond(HttpStatusCode.OK, buildSourceConfig(epgStore, epgRefresher))
        }

        // ---- 手动刷新（异步，202 + 状态可查）----
        post("/api/epg/refresh") {
            val body = call.tryReceive<EpgRefreshRequest>() ?: EpgRefreshRequest()
            val groupId = body.groupId?.trim()?.takeIf { it.isNotEmpty() }

            if (epgRefresher.status.isRunning()) {
                call.respond(HttpStatusCode.Conflict, ApiError("EPG 刷新进行中，请稍后再试"))
                return@post
            }
            // 校验目标源已配置（分组回退全局），避免 202 后才失败
            val sourceUrl = if (groupId != null) {
                val group = epgStore.groupByName(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("分组不存在：$groupId"))
                    return@post
                }
                group.epgUrl?.trim().orEmpty().ifEmpty { epgStore.getGlobalSourceUrl()?.trim().orEmpty() }
            } else {
                epgStore.getGlobalSourceUrl()?.trim().orEmpty()
            }
            if (sourceUrl.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(if (groupId != null) "分组「$groupId」未配置 EPG 源，全局源也未设置" else "未配置全局 EPG 源"),
                )
                return@post
            }

            epgScope.launch {
                try {
                    if (groupId != null) epgRefresher.refreshGroup(groupId) else epgRefresher.refreshGlobal()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 失败状态已在 refresher 内记录（GET /api/epg/source 可见）
                }
            }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "started", "scope" to (groupId ?: "global")))
        }

        // ---- 节目查询 ----
        get("/api/epg/now") {
            val nowMs = System.currentTimeMillis()
            val channels = epgStore.channels()
            val epgIds = channels.mapNotNull { it.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } }.distinct()
            if (epgIds.isEmpty()) {
                call.respond(HttpStatusCode.OK, emptyMap<String, EpgProgramDTO>())
                return@get
            }
            val programs = epgStore.programsByChannelEpgIdsOnce(epgIds, nowMs, nowMs + 1)
            val byEpgId = programs.groupBy { it.channelEpgId }
            val result = HashMap<String, EpgProgramDTO>()
            for (channel in channels) {
                val epgId = channel.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } ?: continue
                val current = byEpgId[epgId]?.firstOrNull() ?: continue
                result[channel.id] = current.toDto()
            }
            call.respond(HttpStatusCode.OK, result)
        }
        get("/api/epg/guide") {
            val channelId = call.request.queryParameters["channelId"]?.trim()
            if (channelId.isNullOrEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少频道 id"))
                return@get
            }
            val channel = epgStore.channelById(channelId)
            if (channel == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("频道不存在：$channelId"))
                return@get
            }
            val zone = ZoneId.systemDefault()
            val dateStr = call.request.queryParameters["date"]
            val day = if (dateStr.isNullOrBlank()) {
                LocalDate.now(zone)
            } else {
                try {
                    LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (_: DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("日期格式应为 yyyy-MM-dd"))
                    return@get
                }
            }
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val epgId = channel.epgId?.trim()?.takeIf { id -> id.isNotEmpty() }
            val programs = if (epgId != null) {
                epgStore.programsByChannelEpgIdOnce(epgId, dayStart, dayEnd)
            } else {
                emptyList()
            }
            call.respond(
                HttpStatusCode.OK,
                EpgGuideDTO(
                    channelId = channelId,
                    date = day.toString(),
                    programs = programs.map { it.toDto() },
                ),
            )
        }
    }
}

/** 组装 GET /api/epg/source 响应（配置 + 状态）。 */
private suspend fun buildSourceConfig(store: EpgStore, refresher: EpgRefreshService): EpgSourceConfigDTO =
    EpgSourceConfigDTO(
        globalUrl = store.getGlobalSourceUrl()?.trim()?.takeIf { it.isNotEmpty() },
        groups = store.groups().map { EpgGroupSourceDTO(name = it.name, epgUrl = it.epgUrl) },
        status = refresher.status.snapshot().toDto(lastUpdate = store.getLastUpdate()),
    )

/** 解析 JSON 请求体；格式错误时返回 400 并返回 null（调用方应就此返回）。 */
private suspend inline fun <reified T> ApplicationCall.receiveBodyOrNull(): T? {
    return try {
        receive()
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ApiError("请求体格式错误：${e.message}"))
        null
    }
}

/** 宽容解析刷新请求体：无 body 或格式错误时按「全局刷新」处理（202 语义宽松）。 */
private suspend inline fun <reified T> ApplicationCall.tryReceive(): T? {
    return try {
        receive()
    } catch (_: Exception) {
        null
    }
}
