package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity

/**
 * 刷新服务单测用的内存实现 [EpgStore]：跨调用保留状态并记录关键操作，便于断言
 * 「解析→匹配→清旧→写库→回写 epgId→last_update」完整链路。
 */
class FakeEpgStore : EpgStore {

    val channels = mutableListOf<ChannelEntity>()
    val groups = mutableListOf<GroupEntity>()
    val programs = mutableListOf<EpgProgramEntity>()

    var globalSourceUrl: String? = null
    var lastUpdate: Long? = null

    /** 记录删除旧数据的 epgId 集合（每次删除调用追加） */
    val deletedEpgIds = mutableListOf<List<String>>()
    /** 记录回写的 (channelId, epgId) 对 */
    val epgIdWrites = mutableListOf<Pair<String, String>>()
    /** 记录清理过期的阈值 */
    val expiredCleanups = mutableListOf<Long>()

    override suspend fun channels(): List<ChannelEntity> = channels.toList()

    override suspend fun channelById(id: String): ChannelEntity? = channels.firstOrNull { it.id == id }

    override suspend fun groups(): List<GroupEntity> = groups.toList()

    override suspend fun groupByName(name: String): GroupEntity? = groups.firstOrNull { it.name == name }

    override suspend fun updateChannelEpgIds(updates: List<Pair<String, String>>) {
        epgIdWrites += updates
        updates.forEach { (id, epgId) ->
            val idx = channels.indexOfFirst { it.id == id }
            if (idx >= 0) channels[idx] = channels[idx].copy(epgId = epgId)
        }
    }

    override suspend fun updateGroupEpgUrl(groupName: String, url: String?) {
        val idx = groups.indexOfFirst { it.name == groupName }
        if (idx >= 0) groups[idx] = groups[idx].copy(epgUrl = url)
    }

    override suspend fun getGlobalSourceUrl(): String? = globalSourceUrl

    override suspend fun setGlobalSourceUrl(url: String) {
        globalSourceUrl = url
    }

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
}
