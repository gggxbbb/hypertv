package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.epg.EpgRefreshService
import icu.gxb.hypertv.epg.EpgStore
import icu.gxb.hypertv.epg.MatchRuleManager
import icu.gxb.hypertv.epg.MatchRuleType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
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
 * EPG API 路由（ticket 09 + v3 多源/规则）：
 * - GET  /api/epg/source            全局多源 + 分组级源 + 刷新状态
 * - POST /api/epg/source            追加全局源 {url}
 * - PUT  /api/epg/source            旧单源兼容：清空全局源并设为该单个源（groupId 仍管理分组覆盖）
 * - PUT  /api/epg/source/{id}       更新全局源 {url?, enabled?}
 * - DELETE /api/epg/source/{id}     删除全局源
 * - POST /api/epg/refresh           202 异步触发刷新（可查状态）
 * - GET  /api/epg/rules             匹配规则列表（含当前命中频道数）
 * - POST /api/epg/rules             新增匹配规则
 * - DELETE /api/epg/rules/{id}      删除匹配规则
 * - POST /api/epg/rules/apply       立即应用全部规则到频道
 * - GET  /api/epg/channels          EPG 频道候选列表（epg_programs 聚合 + 关联频道名样例）
 * - GET  /api/epg/now               Map<channelId, 当前节目>
 * - GET  /api/epg/guide             某频道某天的节目表
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
        // 旧单源 PUT（v3 兼容）：groupId 省略 = 清空现有全局源并设为该单个源（空 url = 清空全部）
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
                // 空 url = 清空全部全局源；非空 = 清空后设为该单个源
                epgStore.replaceEpgSources(if (url.isEmpty()) emptyList() else listOf(url))
            }
            call.respond(HttpStatusCode.OK, buildSourceConfig(epgStore, epgRefresher))
        }
        // 追加全局源
        post("/api/epg/source") {
            val body = call.receiveBodyOrNull<EpgSourceCreateRequest>() ?: return@post
            val url = body.url.trim()
            if (url.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("url 不能为空"))
                return@post
            }
            val created = epgStore.addEpgSource(url)
            call.respond(HttpStatusCode.OK, EpgSourceDTO(id = created.id, url = created.url, enabled = created.enabled))
        }
        // 更新全局源（局部更新）
        put("/api/epg/source/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少有效的源 id"))
                return@put
            }
            val existing = epgStore.epgSourceById(id)
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("EPG 源不存在：$id"))
                return@put
            }
            val body = call.receiveBodyOrNull<EpgSourceUpdateRequest>() ?: return@put
            val newUrl = body.url?.trim()
            if (newUrl != null && newUrl.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("url 不能为空"))
                return@put
            }
            val updated = existing.copy(
                url = newUrl ?: existing.url,
                enabled = body.enabled ?: existing.enabled,
            )
            epgStore.updateEpgSource(updated)
            call.respond(HttpStatusCode.OK, EpgSourceDTO(id = updated.id, url = updated.url, enabled = updated.enabled))
        }
        // 删除全局源
        delete("/api/epg/source/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少有效的源 id"))
                return@delete
            }
            if (epgStore.epgSourceById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("EPG 源不存在：$id"))
                return@delete
            }
            epgStore.deleteEpgSource(id)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- 匹配规则（v3 手动匹配）----
        get("/api/epg/rules") {
            val rules = epgStore.matchRules()
            if (rules.isEmpty()) {
                call.respond(HttpStatusCode.OK, emptyList<EpgRuleDTO>())
                return@get
            }
            // matchedCount = 当前 epgId == 该规则 epgChannelId 的频道数（真实绑定口径）
            val byEpgId = epgStore.channels().mapNotNull { it.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
                .groupingBy { it }
                .eachCount()
            call.respond(
                HttpStatusCode.OK,
                rules.map { rule ->
                    EpgRuleDTO(
                        id = rule.id,
                        epgChannelId = rule.epgChannelId,
                        keyword = rule.keyword,
                        ruleType = rule.ruleType,
                        matchedCount = byEpgId[rule.epgChannelId] ?: 0,
                    )
                },
            )
        }
        post("/api/epg/rules") {
            val body = call.receiveBodyOrNull<EpgRuleRequest>() ?: return@post
            val epgChannelId = body.epgChannelId.trim()
            val keyword = body.keyword.trim()
            if (epgChannelId.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("epgChannelId 不能为空"))
                return@post
            }
            if (keyword.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("keyword 不能为空"))
                return@post
            }
            if (body.ruleType != MatchRuleType.PREFIX && body.ruleType != MatchRuleType.CONTAINS) {
                call.respond(HttpStatusCode.BadRequest, ApiError("ruleType 只能是 prefix 或 contains"))
                return@post
            }
            val created = epgStore.addMatchRule(
                EpgMatchRuleEntity(epgChannelId = epgChannelId, keyword = keyword, ruleType = body.ruleType),
            )
            call.respond(
                HttpStatusCode.OK,
                EpgRuleDTO(id = created.id, epgChannelId = created.epgChannelId, keyword = created.keyword, ruleType = created.ruleType, matchedCount = 0),
            )
        }
        delete("/api/epg/rules/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("缺少有效的规则 id"))
                return@delete
            }
            if (epgStore.matchRules().none { it.id == id }) {
                call.respond(HttpStatusCode.NotFound, ApiError("规则不存在：$id"))
                return@delete
            }
            epgStore.deleteMatchRule(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/epg/rules/apply") {
            val applied = MatchRuleManager(epgStore).applyAll()
            call.respond(HttpStatusCode.OK, EpgRuleApplyResult(applied = applied))
        }

        // ---- EPG 频道候选列表（规则页下拉 / 命中频道展示）----
        get("/api/epg/channels") {
            val epgIds = epgStore.distinctProgramEpgChannelIds()
            if (epgIds.isEmpty()) {
                call.respond(HttpStatusCode.OK, emptyList<EpgChannelCandidateDTO>())
                return@get
            }
            val namesByEpgId = epgStore.channels()
                .groupBy { it.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key!! }
                .mapValues { (_, channels) -> channels.map { it.name }.distinct().take(CANDIDATE_SAMPLE_LIMIT) }
            call.respond(
                HttpStatusCode.OK,
                epgIds.map { id -> EpgChannelCandidateDTO(epgId = id, channelNames = namesByEpgId[id].orEmpty()) },
            )
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
            val sourceConfigured = if (groupId != null) {
                val group = epgStore.groupByName(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("分组不存在：$groupId"))
                    return@post
                }
                !group.epgUrl.isNullOrBlank() || epgStore.epgEnabledSources().isNotEmpty()
            } else {
                epgStore.epgEnabledSources().isNotEmpty()
            }
            if (!sourceConfigured) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(if (groupId != null) "分组「$groupId」未配置 EPG 源，全局源也未设置" else "未配置启用的全局 EPG 源"),
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

/** 组装 GET /api/epg/source 响应（全局多源 + 分组级源 + 刷新状态）。 */
private suspend fun buildSourceConfig(store: EpgStore, refresher: EpgRefreshService): EpgSourceConfigDTO =
    EpgSourceConfigDTO(
        sources = store.epgSources().map { EpgSourceDTO(id = it.id, url = it.url, enabled = it.enabled) },
        groupSources = store.groups().map { EpgGroupSourceDTO(groupName = it.name, url = it.epgUrl) },
        status = refresher.status.snapshot().toDto(lastUpdate = store.getLastUpdate()),
    )

/** 规则页候选列表里每个 EPG 频道最多展示的关联频道名样例数。 */
private const val CANDIDATE_SAMPLE_LIMIT = 5

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
