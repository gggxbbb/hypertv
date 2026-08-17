package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity

/**
 * 频道/分组管理 API 所需的数据层入口（Repository 的窄接口）。
 *
 * 独立成接口以便 Ktor 路由单测注入内存实现/fake；真实实现见
 * [HypertvChannelManagementStore]，由 [HypertvRepository] 适配。
 */
interface ChannelManagementStore {

    /** 全部频道（按全局 orderIndex 升序，含隐藏），过滤由调用方决定 */
    suspend fun channels(): List<ChannelEntity>

    /** 收藏频道（按 orderIndex 升序） */
    suspend fun favoriteChannels(): List<ChannelEntity>

    /** 按 id 一次性读取（不存在返回 null） */
    suspend fun channelById(id: String): ChannelEntity?

    /** 覆盖式更新频道（调用方先读再合并字段） */
    suspend fun updateChannel(channel: ChannelEntity)

    /** 物理删除频道（不可恢复） */
    suspend fun deleteChannel(id: String)

    /** 批量重排频道：按传入顺序赋 orderIndex，未列出的频道保持原相对顺序排到末尾 */
    suspend fun reorderChannels(orderedIds: List<String>)

    suspend fun setChannelFavorite(id: String, favorite: Boolean)

    /** 全部分组（按 orderIndex 升序） */
    suspend fun groups(): List<GroupEntity>

    /** 创建/覆盖分组（重命名时由调用方处理频道归组与旧名删除） */
    suspend fun upsertGroup(group: GroupEntity)

    /** 删除分组；组内频道归入"未分组"（groupName 置空） */
    suspend fun deleteGroup(name: String)

    /** 批量重排分组：按传入顺序赋 orderIndex，未列出的分组保持原相对顺序排到末尾 */
    suspend fun reorderGroups(orderedNames: List<String>)

    /** 批量改分组（WebUI 拖拽入组）；groupName 为空字符串表示归入"未分组" */
    suspend fun moveChannelsToGroup(ids: List<String>, groupName: String)
}
