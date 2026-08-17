package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.ChannelEpgMatchUpdate
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
import icu.gxb.hypertv.data.entity.GroupEntity

/**
 * 刷新服务单测用的内存实现 [EpgStore]：跨调用保留状态并记录关键操作，便于断言
 * 「解析→匹配→清旧→写库→回写 epgId→last_update」完整链路。
 */
class FakeEpgStore : EpgStore {

    val channels = mutableListOf<ChannelEntity>()
    val groups = mutableListOf<GroupEntity>()
    val programs = mutableListOf<EpgProgramEntity>()
    val sources = mutableListOf<EpgSourceEntity>()
    val rules = mutableListOf<EpgMatchRuleEntity>()
    val epgChannels = mutableListOf<EpgChannelEntity>()

    var lastUpdate: Long? = null

    /** 记录删除旧数据的 epgId 集合（每次删除调用追加） */
    val deletedEpgIds = mutableListOf<List<String>>()
    /** 记录回写的 (channelId, epgId) 对 */
    val epgIdWrites = mutableListOf<Pair<String, String>>()
    /** 记录清理过期的阈值 */
    val expiredCleanups = mutableListOf<Long>()

    // ---- 全局 EPG 源 ----

    override suspend fun epgSources(): List<EpgSourceEntity> = sources.sortedBy { it.orderIndex }

    override suspend fun epgEnabledSources(): List<EpgSourceEntity> =
        sources.sortedBy { it.orderIndex }.filter { it.enabled }

    override suspend fun epgSourceById(id: Long): EpgSourceEntity? = sources.firstOrNull { it.id == id }

    override suspend fun addEpgSource(url: String): EpgSourceEntity {
        val orderIndex = (sources.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val source = EpgSourceEntity(id = (sources.maxOfOrNull { it.id } ?: 0) + 1, url = url, enabled = true, orderIndex = orderIndex)
        sources += source
        return source
    }

    override suspend fun updateEpgSource(source: EpgSourceEntity) {
        val idx = sources.indexOfFirst { it.id == source.id }
        if (idx >= 0) sources[idx] = source
    }

    override suspend fun deleteEpgSource(id: Long) {
        sources.removeAll { it.id == id }
    }

    override suspend fun replaceEpgSources(urls: List<String>) {
        sources.clear()
        sources += urls.mapIndexed { index, url ->
            EpgSourceEntity(id = index + 1L, url = url, enabled = true, orderIndex = index)
        }
    }

    // ---- 匹配规则 ----

    override suspend fun matchRules(): List<EpgMatchRuleEntity> = rules.toList()

    override suspend fun addMatchRule(rule: EpgMatchRuleEntity): EpgMatchRuleEntity {
        val saved = if (rule.id == 0L) rule.copy(id = (rules.maxOfOrNull { it.id } ?: 0) + 1) else rule
        rules += saved
        return saved
    }

    override suspend fun deleteMatchRule(id: Long) {
        rules.removeAll { it.id == id }
    }

    // ---- 频道 / 分组 ----

    override suspend fun channels(): List<ChannelEntity> = channels.toList()

    override suspend fun channelById(id: String): ChannelEntity? = channels.firstOrNull { it.id == id }

    override suspend fun groups(): List<GroupEntity> = groups.toList()

    override suspend fun groupByName(name: String): GroupEntity? = groups.firstOrNull { it.name == name }

    override suspend fun updateChannelEpgMatches(updates: List<ChannelEpgMatchUpdate>) {
        epgIdWrites += updates.map { it.channelId to it.epgId }
        updates.forEach { update ->
            val idx = channels.indexOfFirst { it.id == update.channelId }
            if (idx >= 0) {
                channels[idx] = channels[idx].copy(epgId = update.epgId, epgMatchSource = update.source)
            }
        }
    }

    override suspend fun updateGroupEpgUrl(groupName: String, url: String?) {
        val idx = groups.indexOfFirst { it.name == groupName }
        if (idx >= 0) groups[idx] = groups[idx].copy(epgUrl = url)
    }

    // ---- 节目 ----

    override suspend fun getLastUpdate(): Long? = lastUpdate

    override suspend fun setLastUpdate(timestamp: Long) {
        lastUpdate = timestamp
    }

    override suspend fun deleteProgramsByChannelEpgIds(channelEpgIds: List<String>) {
        if (channelEpgIds.isNotEmpty()) {
            deletedEpgIds += channelEpgIds
            programs.removeAll { it.channelEpgId in channelEpgIds }
        }
    }

    override suspend fun upsertPrograms(programs: List<EpgProgramEntity>) {
        programs.forEach { p ->
            this.programs.removeAll { it.id == p.id }
            this.programs.add(p)
        }
    }

    override suspend fun deleteExpiredPrograms(threshold: Long) {
        expiredCleanups += threshold
        programs.removeAll { it.endTime < threshold }
    }

    override suspend fun programsByChannelEpgIdsOnce(
        channelEpgIds: List<String>,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> =
        programs.filter { it.channelEpgId in channelEpgIds && it.startTime < end && it.endTime > start }
            .sortedBy { it.startTime }

    override suspend fun programsByChannelEpgIdOnce(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> =
        programs.filter { it.channelEpgId == channelEpgId && it.startTime < end && it.endTime > start }
            .sortedBy { it.startTime }

    // ---- EPG 频道目录（v4）----

    override suspend fun epgChannelsOnce(): List<EpgChannelEntity> = epgChannels.toList()

    override suspend fun upsertEpgChannels(channels: List<EpgChannelEntity>) {
        channels.forEach { ch ->
            epgChannels.removeAll { it.id == ch.id }
            epgChannels.add(ch)
        }
    }
}
