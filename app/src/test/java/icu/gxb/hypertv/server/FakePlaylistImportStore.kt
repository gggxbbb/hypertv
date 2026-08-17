package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity

/** 路由/导入测试用的内存实现 [PlaylistImportStore]，跨请求保留状态。 */
class FakePlaylistImportStore : PlaylistImportStore {

    private val sources = mutableMapOf<String, PlaylistSourceEntity>()
    private val channels = mutableMapOf<String, MutableList<ChannelEntity>>()

    fun sourceById(id: String): PlaylistSourceEntity? = sources[id]

    fun sources(): List<PlaylistSourceEntity> = sources.values.toList()

    fun channelsOf(sourceId: String): List<ChannelEntity> = channels[sourceId]?.toList() ?: emptyList()

    override suspend fun sourceByUrl(url: String): PlaylistSourceEntity? =
        sources.values.firstOrNull { it.url == url }

    override suspend fun upsertSource(source: PlaylistSourceEntity) {
        sources[source.id] = source
    }

    override suspend fun channelsBySource(sourceId: String): List<ChannelEntity> =
        channels[sourceId]?.toList() ?: emptyList()

    override suspend fun applyImport(
        source: PlaylistSourceEntity,
        inserts: List<ChannelEntity>,
        updates: List<ChannelEntity>,
        hides: List<ChannelEntity>,
    ) {
        sources[source.id] = source
        val list = channels.getOrPut(source.id) { mutableListOf() }
        (inserts + updates + hides).forEach { ch ->
            list.removeAll { it.id == ch.id }
            list.add(ch)
        }
        list.sortBy { it.orderIndex }
    }
}
