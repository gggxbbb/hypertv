package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity

/** 路由/管理测试用的内存实现 [ChannelManagementStore]，跨请求保留状态。 */
class FakeChannelManagementStore : ChannelManagementStore {

    private val channels = mutableMapOf<String, ChannelEntity>()
    private val groups = mutableMapOf<String, GroupEntity>()

    fun seedChannel(channel: ChannelEntity) {
        channels[channel.id] = channel
    }

    fun seedGroup(group: GroupEntity) {
        groups[group.name] = group
    }

    fun channel(id: String): ChannelEntity? = channels[id]

    fun channelList(): List<ChannelEntity> = channels.values.sortedBy { it.orderIndex }

    fun groupList(): List<GroupEntity> = groups.values.sortedBy { it.orderIndex }

    override suspend fun channels(): List<ChannelEntity> = channelList()

    override suspend fun favoriteChannels(): List<ChannelEntity> = channelList().filter { it.isFavorite }

    override suspend fun channelById(id: String): ChannelEntity? = channels[id]

    override suspend fun updateChannel(channel: ChannelEntity) {
        channels[channel.id] = channel
    }

    override suspend fun deleteChannel(id: String) {
        channels.remove(id)
    }

    override suspend fun reorderChannels(orderedIds: List<String>) {
        val current = channelList()
        val byId = current.associateBy { it.id }
        val present = orderedIds.mapNotNull { byId[it] }
        val rest = current.filter { it.id !in orderedIds }
        (present + rest).forEachIndexed { index, ch -> channels[ch.id] = ch.copy(orderIndex = index) }
    }

    override suspend fun setChannelFavorite(id: String, favorite: Boolean) {
        channels[id]?.let { channels[id] = it.copy(isFavorite = favorite) }
    }

    override suspend fun groups(): List<GroupEntity> = groupList()

    override suspend fun upsertGroup(group: GroupEntity) {
        groups[group.name] = group
    }

    override suspend fun deleteGroup(name: String) {
        groups.remove(name)
        channels.values.filter { it.groupName == name }.forEach { channels[it.id] = it.copy(groupName = "") }
    }

    override suspend fun reorderGroups(orderedNames: List<String>) {
        val current = groupList()
        val present = orderedNames.mapNotNull { n -> current.firstOrNull { it.name == n } }
        val rest = current.filter { it.name !in orderedNames }
        (present + rest).forEachIndexed { index, g -> groups[g.name] = g.copy(orderIndex = index) }
    }

    override suspend fun moveChannelsToGroup(ids: List<String>, groupName: String) {
        ids.forEach { id -> channels[id]?.let { channels[id] = it.copy(groupName = groupName) } }
    }
}
