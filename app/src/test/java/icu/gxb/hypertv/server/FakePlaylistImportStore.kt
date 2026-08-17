package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity

/** 路由/导入测试用的内存实现 [PlaylistImportStore]，跨请求保留状态。 */
class FakePlaylistImportStore : PlaylistImportStore {

    private val sources = mutableMapOf<String, PlaylistSourceEntity>()
    private val channels = mutableMapOf<String, MutableList<ChannelEntity>>()

    fun channelsOf(sourceId: String): List<ChannelEntity> = channels[sourceId]?.toList() ?: emptyList()

    override suspend fun sources(): List<PlaylistSourceEntity> = sources.values.toList()

    override suspend fun sourceById(id: String): PlaylistSourceEntity? = sources[id]

    override suspend fun sourceByUrl(url: String): PlaylistSourceEntity? =
        sources.values.firstOrNull { it.url == url }

    override suspend fun sourceByNameAndType(name: String, type: String): PlaylistSourceEntity? =
        sources.values.firstOrNull { it.name == name && it.type == type }

    override suspend fun upsertSource(source: PlaylistSourceEntity) {
        sources[source.id] = source
    }

    /** 模拟外键级联删除：删除源的同时清空其全部频道（含收藏，ADR-0004） */
    override suspend fun deleteSource(id: String) {
        sources.remove(id)
        channels.remove(id)
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
