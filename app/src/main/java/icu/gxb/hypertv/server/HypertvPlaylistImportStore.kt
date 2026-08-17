package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import icu.gxb.hypertv.data.repository.HypertvRepository

/** [PlaylistImportStore] 的真实实现：适配 [HypertvRepository]。 */
class HypertvPlaylistImportStore(
    private val repository: HypertvRepository,
) : PlaylistImportStore {

    override suspend fun sources(): List<PlaylistSourceEntity> = repository.playlistSourcesOnce()

    override suspend fun sourceById(id: String): PlaylistSourceEntity? = repository.playlistSourceById(id)

    override suspend fun sourceByUrl(url: String): PlaylistSourceEntity? = repository.playlistSourceByUrl(url)

    override suspend fun sourceByNameAndType(name: String, type: String): PlaylistSourceEntity? =
        repository.playlistSourceByNameAndType(name, type)

    override suspend fun upsertSource(source: PlaylistSourceEntity) = repository.upsertPlaylistSource(source)

    override suspend fun deleteSource(id: String) = repository.deletePlaylistSource(id)

    override suspend fun channelsBySource(sourceId: String): List<ChannelEntity> =
        repository.channelsBySourceOnce(sourceId)

    override suspend fun applyImport(
        source: PlaylistSourceEntity,
        inserts: List<ChannelEntity>,
        updates: List<ChannelEntity>,
        hides: List<ChannelEntity>,
    ) = repository.applyImport(source, inserts, updates, hides)
}
