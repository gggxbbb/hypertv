package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.ChannelEpgMatchUpdate
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
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

    /** 写入频道 epgId + 匹配来源（EPG 匹配/规则后回写，供后续查询直接使用） */
    suspend fun updateChannelEpgMatches(updates: List<ChannelEpgMatchUpdate>)

    /** 设置分组级 EPG 源（url 为 null 表示清除覆盖，回退全局源） */
    suspend fun updateGroupEpgUrl(groupName: String, url: String?)

    // ---- 全局 EPG 源（v3 多源）----

    /** 全部全局 EPG 源（按 orderIndex 升序） */
    suspend fun epgSources(): List<EpgSourceEntity>

    /** 启用中的全局 EPG 源（刷新时按 orderIndex 顺序拉取） */
    suspend fun epgEnabledSources(): List<EpgSourceEntity>

    /** 按 id 读取全局源（不存在返回 null） */
    suspend fun epgSourceById(id: Long): EpgSourceEntity?

    /** 新增全局源（追加到末尾，启用），返回含 id 的实体 */
    suspend fun addEpgSource(url: String): EpgSourceEntity

    /** 覆盖式更新全局源（url/enabled） */
    suspend fun updateEpgSource(source: EpgSourceEntity)

    /** 删除全局源 */
    suspend fun deleteEpgSource(id: Long)

    /** 清空现有全局源并设为给定源（旧 PUT /api/epg/source 单源设置兼容） */
    suspend fun replaceEpgSources(urls: List<String>)

    // ---- 匹配规则（v3 手动匹配）----

    /** 全部匹配规则（按 id 升序） */
    suspend fun matchRules(): List<EpgMatchRuleEntity>

    /** 新增匹配规则，返回含 id 的实体 */
    suspend fun addMatchRule(rule: EpgMatchRuleEntity): EpgMatchRuleEntity

    /** 删除匹配规则 */
    suspend fun deleteMatchRule(id: Long)

    // ---- 节目 ----

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

    // ---- EPG 频道目录（v4）----

    /** 全部持久化的 XMLTV 频道目录（GET /api/epg/channels 用） */
    suspend fun epgChannelsOnce(): List<EpgChannelEntity>

    /** 批量 upsert XMLTV 频道目录（刷新成功后幂等写入，同 id 覆盖 displayName/icon） */
    suspend fun upsertEpgChannels(channels: List<EpgChannelEntity>)
}
