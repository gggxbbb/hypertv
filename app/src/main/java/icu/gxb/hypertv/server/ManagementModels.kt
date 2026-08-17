package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import kotlinx.serialization.Serializable

/** 统一错误响应：非 2xx 时返回 {"error": "..."}。 */
@Serializable
data class ApiError(val error: String)

/** 频道对外 DTO（ticket 07）：频道号 = 全局 orderIndex + 1。 */
@Serializable
data class ChannelDTO(
    val id: String,
    val sourceId: String,
    /** 频道号（全局排序号，与分组无关） */
    val number: Int,
    val name: String,
    val url: String,
    val groupName: String,
    val logoUrl: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val epgId: String?,
    val catchup: String?,
)

fun ChannelEntity.toDto(): ChannelDTO = ChannelDTO(
    id = id,
    sourceId = sourceId,
    number = orderIndex + 1,
    name = name,
    url = url,
    groupName = groupName,
    logoUrl = logoUrl,
    isFavorite = isFavorite,
    isHidden = isHidden,
    epgId = epgId,
    catchup = catchup,
)

/** 分组对外 DTO：附带组内频道数（含隐藏），供 WebUI 展示。 */
@Serializable
data class GroupDTO(
    val name: String,
    val orderIndex: Int,
    val channelCount: Int,
)

fun GroupEntity.toDto(channelCount: Int): GroupDTO = GroupDTO(
    name = name,
    orderIndex = orderIndex,
    channelCount = channelCount,
)

/** PUT /api/channels/{id} 请求体：仅提交需要修改的字段（局部更新）。 */
@Serializable
data class UpdateChannelRequest(
    val name: String? = null,
    val groupName: String? = null,
    val logoUrl: String? = null,
    val isHidden: Boolean? = null,
)

/** POST /api/channels/{id}/favorite 请求体。 */
@Serializable
data class FavoriteRequest(val favorite: Boolean)

/** POST /api/channels/reorder 请求体：目标顺序的频道 id 列表（未列出的频道保持原相对顺序排到末尾）。 */
@Serializable
data class ReorderChannelsRequest(val ids: List<String>)

/** POST /api/groups 请求体：newName 为空表示新建，非空表示把 name 重命名为 newName。 */
@Serializable
data class GroupUpsertRequest(
    val name: String,
    val newName: String? = null,
)

/** POST /api/groups/reorder 请求体：目标顺序的分组名列表（未列出的分组保持原相对顺序排到末尾）。 */
@Serializable
data class ReorderGroupsRequest(val names: List<String>)
