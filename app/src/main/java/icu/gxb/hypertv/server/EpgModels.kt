package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.epg.EpgMatchStats
import icu.gxb.hypertv.epg.EpgRefreshStatusView
import kotlinx.serialization.Serializable

/** 旧 PUT /api/epg/source 请求体（v3 保留兼容）：url 必填；groupId 省略 = 全局单源替换。 */
@Serializable
data class EpgSourceRequest(
    val url: String,
    val groupId: String? = null,
)

/** POST /api/epg/source 请求体：追加一个全局源。 */
@Serializable
data class EpgSourceCreateRequest(
    val url: String,
)

/** PUT /api/epg/source/{id} 请求体：仅提交需要修改的字段（局部更新）。 */
@Serializable
data class EpgSourceUpdateRequest(
    val url: String? = null,
    val enabled: Boolean? = null,
)

/** POST /api/epg/refresh 请求体：groupId 省略 = 全局。 */
@Serializable
data class EpgRefreshRequest(
    val groupId: String? = null,
)

/** 全局 EPG 源对外 DTO（sources 从 epg_sources 表读取）。 */
@Serializable
data class EpgSourceDTO(
    val id: Long,
    val url: String,
    val enabled: Boolean,
)

/** 匹配统计对外 DTO（WebUI 命中率展示）。 */
@Serializable
data class EpgMatchStatsDTO(
    val total: Int,
    val matched: Int,
    val unmatched: Int,
    val level1: Int,
    val level2: Int,
    val level3: Int,
    val level4: Int,
    val rate: Double,
)

fun EpgMatchStats.toDto(): EpgMatchStatsDTO = EpgMatchStatsDTO(
    total = total,
    matched = matched,
    unmatched = unmatched,
    level1 = level1,
    level2 = level2,
    level3 = level3,
    level4 = level4,
    rate = rate,
)

/** 刷新状态对外 DTO（内存态 + 持久化的 lastUpdate）。 */
@Serializable
data class EpgStatusDTO(
    val running: Boolean,
    val scope: String? = null,
    val lastUpdate: Long? = null,
    val lastError: String? = null,
    val stats: EpgMatchStatsDTO? = null,
)

fun EpgRefreshStatusView.toDto(lastUpdate: Long?): EpgStatusDTO = EpgStatusDTO(
    running = running,
    scope = scope,
    lastUpdate = lastUpdate,
    lastError = lastError,
    stats = lastStats?.toDto(),
)

/** GET /api/epg/source 响应：全局多源 + 分组级源 + 刷新状态。 */
@Serializable
data class EpgSourceConfigDTO(
    val sources: List<EpgSourceDTO>,
    val groupSources: List<EpgGroupSourceDTO>,
    val status: EpgStatusDTO,
)

/** 分组级 EPG 源：分组名 + 覆盖的 url（null = 未覆盖，回退全局源）。 */
@Serializable
data class EpgGroupSourceDTO(
    val groupName: String,
    val url: String?,
)

/** 匹配规则对外 DTO（matchedCount = 当前 epgId == 该 epgChannelId 的频道数）。 */
@Serializable
data class EpgRuleDTO(
    val id: Long,
    val epgChannelId: String,
    val keyword: String,
    val ruleType: String,
    val matchedCount: Int,
)

/** POST /api/epg/rules 请求体。 */
@Serializable
data class EpgRuleRequest(
    val epgChannelId: String,
    val keyword: String,
    val ruleType: String,
)

/** POST /api/epg/rules/apply 响应。 */
@Serializable
data class EpgRuleApplyResult(
    val applied: Int,
)

/**
 * GET /api/epg/channels 候选列表项（v4 起从 epg_channels 目录返回，不再聚合 epg_programs）。
 * 保留原 epgId/channelNames 字段名向后兼容；新增 displayName/icon/matchedCount 供 WebUI 辨认频道。
 */
@Serializable
data class EpgChannelCandidateDTO(
    val epgId: String,
    /** XMLTV 频道展示名（持久化在 epg_channels，刷新后仍可辨认，如 id=1 → CCTV1） */
    val displayName: String,
    /** XMLTV 频道台标 */
    val icon: String? = null,
    /** 当前 epgId 关联到的本地频道数 */
    val matchedCount: Int,
    /** 关联的本地频道名样例（≤ CANDIDATE_SAMPLE_LIMIT 个，保留原字段名向后兼容） */
    val channelNames: List<String>,
)

/** EPG 节目对外 DTO（now/guide 共用）。 */
@Serializable
data class EpgProgramDTO(
    val id: String,
    /** 关联的 channelEpgId（== 频道 epgId） */
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startTime: Long,
    val endTime: Long,
)

fun EpgProgramEntity.toDto(): EpgProgramDTO = EpgProgramDTO(
    id = id,
    channelId = channelEpgId,
    title = title,
    description = description,
    category = category,
    startTime = startTime,
    endTime = endTime,
)

/** GET /api/epg/guide 响应。 */
@Serializable
data class EpgGuideDTO(
    val channelId: String,
    val date: String,
    val programs: List<EpgProgramDTO>,
)
