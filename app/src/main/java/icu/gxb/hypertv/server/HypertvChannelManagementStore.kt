package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.repository.HypertvRepository

/** [ChannelManagementStore] 的真实实现：适配 [HypertvRepository]。 */
class HypertvChannelManagementStore(
    private val repository: HypertvRepository,
) : ChannelManagementStore {

    override suspend fun channels(): List<ChannelEntity> = repository.channelsOnce()

    override suspend fun favoriteChannels(): List<ChannelEntity> = repository.favoriteChannelsOnce()

    override suspend fun channelById(id: String): ChannelEntity? = repository.channelByIdOnce(id)

    override suspend fun updateChannel(channel: ChannelEntity) = repository.updateChannel(channel)

    override suspend fun deleteChannel(id: String) = repository.deleteChannel(id)

    override suspend fun reorderChannels(orderedIds: List<String>) {
        val current = repository.channelsOnce().sortedBy { it.orderIndex }
        val byId = current.associateBy { it.id }
        val present = orderedIds.mapNotNull { byId[it] }
        val rest = current.filter { it.id !in orderedIds }
        val newOrder = (present + rest).mapIndexed { index, channel -> channel.id to index }
        repository.reorderChannels(newOrder)
    }

    override suspend fun setChannelFavorite(id: String, favorite: Boolean) =
        repository.setChannelFavorite(id, favorite)

    override suspend fun groups(): List<GroupEntity> = repository.groupsOnce()

    override suspend fun upsertGroup(group: GroupEntity) = repository.upsertGroup(group)

    override suspend fun deleteGroup(name: String) = repository.deleteGroupWithChannels(name)

    override suspend fun reorderGroups(orderedNames: List<String>) {
        val current = repository.groupsOnce().sortedBy { it.orderIndex }
        val present = orderedNames.mapNotNull { name -> current.firstOrNull { it.name == name } }
        val rest = current.filter { it.name !in orderedNames }
        val newOrder = (present + rest).mapIndexed { index, group -> group.name to index }
        repository.reorderGroups(newOrder)
    }

    override suspend fun moveChannelsToGroup(ids: List<String>, groupName: String) =
        repository.moveChannelsToGroup(ids, groupName)
}
