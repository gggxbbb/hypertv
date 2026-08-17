package icu.gxb.hypertv.data.repository

import icu.gxb.hypertv.data.dao.AppConfigDao
import icu.gxb.hypertv.data.dao.ChannelDao
import icu.gxb.hypertv.data.dao.EpgProgramDao
import icu.gxb.hypertv.data.dao.GroupDao
import icu.gxb.hypertv.data.dao.PlaylistSourceDao
import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 统一数据入口：TV 端 UI 与 Ktor API 通过本类访问数据层。
 *
 * - Flow 由 Room 自动在 io dispatcher 上发射，订阅方按需切换调度
 * - 写入方法均为 suspend，事务边界（如批量 reorder）在 DAO 内保证
 * - 级联删除由数据库外键 CASCADE 保证（删除直播源 → 频道级联删除）
 */
class HypertvRepository(
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val playlistSourceDao: PlaylistSourceDao,
    private val epgProgramDao: EpgProgramDao,
    private val appConfigDao: AppConfigDao,
) {

    // ---- 频道 ----

    /** 全部频道（按全局 orderIndex 升序） */
    val channels: Flow<List<ChannelEntity>> = channelDao.getAll()

    /** 收藏频道（按 orderIndex 升序） */
    val favoriteChannels: Flow<List<ChannelEntity>> = channelDao.getFavorites()

    fun channelById(id: String): Flow<ChannelEntity?> = channelDao.getById(id)

    fun channelsBySource(sourceId: String): Flow<List<ChannelEntity>> = channelDao.getBySourceId(sourceId)

    suspend fun addChannels(channels: List<ChannelEntity>) = channelDao.upsertAll(channels)

    suspend fun addChannel(channel: ChannelEntity) = channelDao.upsert(channel)

    suspend fun updateChannel(channel: ChannelEntity) = channelDao.update(channel)

    suspend fun deleteChannel(id: String) = channelDao.deleteById(id)

    suspend fun deleteChannelsBySource(sourceId: String) = channelDao.deleteBySourceId(sourceId)

    /** 增量合并按 URL 匹配现有频道（ADR-0004），URL 需调用方归一化 */
    suspend fun channelByUrl(url: String): ChannelEntity? = channelDao.getByUrl(url)

    /** 批量重排频道（原子事务） */
    suspend fun reorderChannels(newOrder: List<Pair<String, Int>>) = channelDao.reorder(newOrder)

    suspend fun setChannelFavorite(id: String, isFavorite: Boolean) = channelDao.setFavorite(id, isFavorite)

    suspend fun setChannelHidden(id: String, isHidden: Boolean) = channelDao.setHidden(id, isHidden)

    // ---- 分组 ----

    val groups: Flow<List<GroupEntity>> = groupDao.getAll()

    suspend fun upsertGroup(group: GroupEntity) = groupDao.upsert(group)

    suspend fun deleteGroup(name: String) = groupDao.deleteByName(name)

    suspend fun reorderGroups(newOrder: List<Pair<String, Int>>) = groupDao.reorder(newOrder)

    // ---- 直播源 ----

    val playlistSources: Flow<List<PlaylistSourceEntity>> = playlistSourceDao.getAll()

    suspend fun playlistSourceById(id: String): PlaylistSourceEntity? = playlistSourceDao.getById(id)

    suspend fun upsertPlaylistSource(source: PlaylistSourceEntity) = playlistSourceDao.upsert(source)

    /** 删除直播源（其频道由外键级联删除，含收藏记录，ADR-0004） */
    suspend fun deletePlaylistSource(id: String) = playlistSourceDao.deleteById(id)

    // ---- EPG ----

    suspend fun upsertPrograms(programs: List<EpgProgramEntity>) = epgProgramDao.upsertAll(programs)

    fun programs(channelEpgId: String, start: Long, end: Long): Flow<List<EpgProgramEntity>> =
        epgProgramDao.getByChannelAndTime(channelEpgId, start, end)

    suspend fun deleteExpiredPrograms(threshold: Long) = epgProgramDao.deleteExpired(threshold)

    // ---- 应用配置 ----

    suspend fun getConfig(key: String): String? = appConfigDao.get(key)

    suspend fun putConfig(key: String, value: String) = appConfigDao.put(key, value)
}
