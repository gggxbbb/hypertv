package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
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

    override suspend fun updateChannelEpgIds(updates: List<Pair<String, String>>) =
        repository.updateChannelEpgIds(updates)

    override suspend fun updateGroupEpgUrl(groupName: String, url: String?) =
        repository.updateGroupEpgUrl(groupName, url)

    override suspend fun getGlobalSourceUrl(): String? = repository.getConfig(KEY_GLOBAL_SOURCE)

    override suspend fun setGlobalSourceUrl(url: String) = repository.putConfig(KEY_GLOBAL_SOURCE, url)

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

    companion object {
        /** app_config 键（ticket 09 约定）。 */
        const val KEY_GLOBAL_SOURCE = "epg_source_url"
        const val KEY_LAST_UPDATE = "epg_last_update"
    }
}
