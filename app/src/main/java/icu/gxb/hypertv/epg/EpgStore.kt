package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity

/**
 * EPG 刷新/查询所需的数据层入口（Repository 的窄接口）。
 *
 * 独立成接口以便 Ktor 路由单测与刷新服务单测注入内存实现/fake；
 * 真实实现见 server 包 [icu.gxb.hypertv.server.HypertvEpgStore]。
 */
interface EpgStore {

    /** 全部频道（按全局 orderIndex 升序，含隐藏） */
    suspend fun channels(): List<ChannelEntity>

    /** 按 id 一次性读取频道（不存在返回 null） */
    suspend fun channelById(id: String): ChannelEntity?

    /** 全部分组（按 orderIndex 升序） */
    suspend fun groups(): List<GroupEntity>

    /** 按名称读取分组（不存在返回 null） */
    suspend fun groupByName(name: String): GroupEntity?

    /** 写入频道 epgId（EPG 匹配后回写 xmltvId，供后续查询直接使用） */
    suspend fun updateChannelEpgIds(updates: List<Pair<String, String>>)

    /** 设置分组级 EPG 源（url 为 null 表示清除覆盖，回退全局源） */
    suspend fun updateGroupEpgUrl(groupName: String, url: String?)

    /** 全局 EPG 源 URL（未配置/已清除返回 null 或空串） */
    suspend fun getGlobalSourceUrl(): String?

    /** 写入全局 EPG 源 URL（空串表示清除） */
    suspend fun setGlobalSourceUrl(url: String)

    /** 上次成功刷新时间（app_config epg_last_update；从未刷新返回 null） */
    suspend fun getLastUpdate(): Long?

    /** 记录成功刷新时间 */
    suspend fun setLastUpdate(timestamp: Long)

    /** 删除这些 channelEpgId 的全部节目（刷新前清旧数据） */
    suspend fun deleteProgramsByChannelEpgIds(channelEpgIds: List<String>)

    /** 批量写入节目（一次事务） */
    suspend fun upsertPrograms(programs: List<EpgProgramEntity>)

    /** 清理已全部结束的过期节目（endTime < threshold） */
    suspend fun deleteExpiredPrograms(threshold: Long)

    /** 一次性查询多个频道在 [start, end) 窗口内的节目（按 startTime 升序） */
    suspend fun programsByChannelEpgIdsOnce(
        channelEpgIds: List<String>,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity>

    /** 一次性查询单频道在 [start, end) 窗口内的节目（按 startTime 升序） */
    suspend fun programsByChannelEpgIdOnce(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity>
}
