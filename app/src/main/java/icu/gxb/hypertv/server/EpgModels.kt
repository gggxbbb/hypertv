package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.epg.EpgMatchStats
import icu.gxb.hypertv.epg.EpgRefreshStatusView
import kotlinx.serialization.Serializable

/** PUT /api/epg/source 请求体：url 必填（空串/空白 = 清除）；groupId 省略 = 全局。 */
@Serializable
data class EpgSourceRequest(
    val url: String,
    val groupId: String? = null,
)

/** POST /api/epg/refresh 请求体：groupId 省略 = 全局。 */
@Serializable
data class EpgRefreshRequest(
    val groupId: String? = null,
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
    val rate: Double,
)

fun EpgMatchStats.toDto(): EpgMatchStatsDTO = EpgMatchStatsDTO(
    total = total,
    matched = matched,
    unmatched = unmatched,
    level1 = level1,
    level2 = level2,
    level3 = level3,
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

/** GET /api/epg/source 响应：全局源 + 全部分组（含分组级覆盖）+ 刷新状态。 */
@Serializable
data class EpgSourceConfigDTO(
    val globalUrl: String?,
    val groups: List<EpgGroupSourceDTO>,
    val status: EpgStatusDTO,
)

@Serializable
data class EpgGroupSourceDTO(
    val name: String,
    val epgUrl: String?,
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
