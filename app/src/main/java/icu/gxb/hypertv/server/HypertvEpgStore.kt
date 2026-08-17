package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.ChannelEpgMatchUpdate
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.epg.EpgStore

/** [EpgStore] 的真实实现：适配 [HypertvRepository]。 */
class HypertvEpgStore(
    private val repository: HypertvRepository,
) : EpgStore {

    override suspend fun channels(): List<ChannelEntity> = repository.channelsOnce()

    override suspend fun channelById(id: String): ChannelEntity? = repository.channelByIdOnce(id)

    override suspend fun groups(): List<GroupEntity> = repository.groupsOnce()

    override suspend fun groupByName(name: String): GroupEntity? = repository.groupByNameOnce(name)

    override suspend fun updateChannelEpgMatches(updates: List<ChannelEpgMatchUpdate>) =
        repository.updateChannelEpgMatches(updates)

    override suspend fun updateGroupEpgUrl(groupName: String, url: String?) =
        repository.updateGroupEpgUrl(groupName, url)

    override suspend fun epgSources(): List<EpgSourceEntity> = repository.epgSourcesOnce()

    override suspend fun epgEnabledSources(): List<EpgSourceEntity> = repository.epgEnabledSourcesOnce()

    override suspend fun epgSourceById(id: Long): EpgSourceEntity? = repository.epgSourceById(id)

    override suspend fun addEpgSource(url: String): EpgSourceEntity = repository.addEpgSource(url)

    override suspend fun updateEpgSource(source: EpgSourceEntity) {
        repository.updateEpgSource(source)
    }

    override suspend fun deleteEpgSource(id: Long) = repository.deleteEpgSource(id)

    override suspend fun replaceEpgSources(urls: List<String>) = repository.replaceEpgSources(urls)

    override suspend fun matchRules(): List<EpgMatchRuleEntity> = repository.epgMatchRulesOnce()

    override suspend fun addMatchRule(rule: EpgMatchRuleEntity): EpgMatchRuleEntity =
        repository.addEpgMatchRule(rule)

    override suspend fun deleteMatchRule(id: Long) = repository.deleteEpgMatchRule(id)

    override suspend fun getLastUpdate(): Long? =
        repository.getConfig(KEY_LAST_UPDATE)?.toLongOrNull()

    override suspend fun setLastUpdate(timestamp: Long) =
        repository.putConfig(KEY_LAST_UPDATE, timestamp.toString())

    override suspend fun deleteProgramsByChannelEpgIds(channelEpgIds: List<String>) {
        if (channelEpgIds.isNotEmpty()) repository.deleteProgramsByChannelEpgIds(channelEpgIds)
    }

    override suspend fun upsertPrograms(programs: List<EpgProgramEntity>) =
        repository.upsertPrograms(programs)

    override suspend fun deleteExpiredPrograms(threshold: Long) =
        repository.deleteExpiredPrograms(threshold)

    override suspend fun programsByChannelEpgIdsOnce(
        channelEpgIds: List<String>,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> {
        if (channelEpgIds.isEmpty()) return emptyList()
        return repository.programsByChannelEpgIdsOnce(channelEpgIds, start, end)
    }

    override suspend fun programsByChannelEpgIdOnce(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> = repository.programsByChannelEpgIdOnce(channelEpgId, start, end)

    override suspend fun epgChannelsOnce(): List<EpgChannelEntity> = repository.epgChannelsOnce()

    override suspend fun upsertEpgChannels(channels: List<EpgChannelEntity>) =
        repository.upsertEpgChannels(channels)

    companion object {
        /** app_config 键（v3 起全局源改存 epg_sources 表，旧 epg_source_url 键已废弃）。 */
        const val KEY_LAST_UPDATE = "epg_last_update"
    }
}
