package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import kotlinx.serialization.Serializable

/** 统一错误响应：非 2xx 时返回 {"error": "..."}。 */
@Serializable
data class ApiError(val error: String)

/** 频道对外 DTO（ticket 07 + v3 + v5）：频道号 = 排序后列表位置 + 1（动态生成，永远连续无空洞）。 */
@Serializable
data class ChannelDTO(
    val id: String,
    val sourceId: String,
    /** 频道号（排序后索引 + 1，与分组无关，不依赖 orderIndex 连续性） */
    val number: Int,
    val name: String,
    val url: String,
    val groupName: String,
    val logoUrl: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val epgId: String?,
    /** EPG 匹配来源（v5）：null=未匹配；"manual" | "rule" | "level1"~"level5" */
    val epgMatchSource: String?,
    val catchup: String?,
)

/**
 * 频道实体 → DTO；频道号由调用方传入（= 在响应列表中按 orderIndex 排序后的位置 + 1）。
 */
fun ChannelEntity.toDto(number: Int): ChannelDTO = ChannelDTO(
    id = id,
    sourceId = sourceId,
    number = number,
    name = name,
    url = url,
    groupName = groupName,
    logoUrl = logoUrl,
    isFavorite = isFavorite,
    isHidden = isHidden,
    epgId = epgId,
    epgMatchSource = epgMatchSource,
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

/**
 * PUT /api/channels/{id} 请求体：仅提交需要修改的字段（局部更新）。
 *
 * [epgId]：EPG 手动绑定（v3）。缺省 = 不修改；传 null = 清除绑定（epgManual 复位）；
 * 传非空 = 绑定并置 epgManual=true（刷新/重导入不再覆盖）。
 */
@Serializable
data class UpdateChannelRequest(
    val name: String? = null,
    val groupName: String? = null,
    val logoUrl: String? = null,
    val isHidden: Boolean? = null,
    val epgId: String? = EPG_ID_UNSET,
) {
    companion object {
        /** 缺省哨兵：kotlinx.serialization 无法区分「缺省」与「显式 null」，用哨兵区分 */
        const val EPG_ID_UNSET = "\u0000EPG_ID_UNSET\u0000"
    }
}

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

/** 直播源对外 DTO：附加该源当前频道数（含隐藏），供 WebUI 列表展示。 */
@Serializable
data class PlaylistDTO(
    val id: String,
    val name: String,
    /** "url" 或 "file" */
    val type: String,
    val url: String,
    val channelCount: Int,
    val lastImportedAt: Long,
)

fun PlaylistSourceEntity.toPlaylistDto(channelCount: Int): PlaylistDTO = PlaylistDTO(
    id = id,
    name = name,
    type = type,
    url = url,
    channelCount = channelCount,
    lastImportedAt = lastImportedAt,
)

/** PUT /api/playlists/{id} 请求体：直播源重命名。 */
@Serializable
data class RenamePlaylistRequest(val name: String)
