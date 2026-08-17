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

    /** 一次性（非 Flow）读取全部收藏频道，供管理 API 使用 */
    suspend fun favoriteChannelsOnce(): List<ChannelEntity> = channelDao.getFavoritesOnce()

    /** 一次性（非 Flow）读取全部频道，供管理 API 等一次性任务使用 */
    suspend fun channelsOnce(): List<ChannelEntity> = channelDao.getAllOnce()

    fun channelById(id: String): Flow<ChannelEntity?> = channelDao.getById(id)

    /** 一次性（非 Flow）按 id 读取，供管理 API 编辑合并字段时使用 */
    suspend fun channelByIdOnce(id: String): ChannelEntity? = channelDao.getByIdOnce(id)

    fun channelsBySource(sourceId: String): Flow<List<ChannelEntity>> = channelDao.getBySourceId(sourceId)

    /** 一次性（非 Flow）按源查询，供导入等一次性任务使用 */
    suspend fun channelsBySourceOnce(sourceId: String): List<ChannelEntity> = channelDao.getBySourceIdOnce(sourceId)

    suspend fun addChannels(channels: List<ChannelEntity>) = channelDao.upsertAll(channels)

    suspend fun addChannel(channel: ChannelEntity) = channelDao.upsert(channel)

    suspend fun updateChannel(channel: ChannelEntity) = channelDao.update(channel)

    suspend fun deleteChannel(id: String) = channelDao.deleteById(id)

    suspend fun deleteChannelsBySource(sourceId: String) = channelDao.deleteBySourceId(sourceId)

    /** 批量回写频道 epgId（EPG 匹配结果写回，单事务） */
    suspend fun updateChannelEpgIds(updates: List<Pair<String, String>>) = channelDao.updateEpgIds(updates)

    /** 增量合并按 URL 匹配现有频道（ADR-0004），URL 需调用方归一化 */
    suspend fun channelByUrl(url: String): ChannelEntity? = channelDao.getByUrl(url)

    /** 批量重排频道（原子事务） */
    suspend fun reorderChannels(newOrder: List<Pair<String, Int>>) = channelDao.reorder(newOrder)

    suspend fun setChannelFavorite(id: String, isFavorite: Boolean) = channelDao.setFavorite(id, isFavorite)

    suspend fun setChannelHidden(id: String, isHidden: Boolean) = channelDao.setHidden(id, isHidden)

    // ---- 分组 ----

    val groups: Flow<List<GroupEntity>> = groupDao.getAll()

    /** 一次性（非 Flow）读取全部分组，供管理 API 使用 */
    suspend fun groupsOnce(): List<GroupEntity> = groupDao.getAllOnce()

    suspend fun groupByNameOnce(name: String): GroupEntity? = groupDao.getByNameOnce(name)

    /** 设置分组级 EPG 源 URL（null 表示清除覆盖，回退全局源） */
    suspend fun updateGroupEpgUrl(name: String, url: String?) = groupDao.updateEpgUrl(name, url)

    suspend fun upsertGroup(group: GroupEntity) = groupDao.upsert(group)

    /** 删除分组并把组内频道归入"未分组"（WebUI 分组删除走这里，事务保证） */
    suspend fun deleteGroupWithChannels(name: String) = groupDao.deleteGroupWithChannels(name)

    suspend fun deleteGroup(name: String) = groupDao.deleteByName(name)

    /** 批量改分组（WebUI 拖拽入组）；groupName 为空字符串表示归入"未分组" */
    suspend fun moveChannelsToGroup(ids: List<String>, groupName: String) = channelDao.setGroupForIds(ids, groupName)

    suspend fun reorderGroups(newOrder: List<Pair<String, Int>>) = groupDao.reorder(newOrder)

    // ---- 直播源 ----

    val playlistSources: Flow<List<PlaylistSourceEntity>> = playlistSourceDao.getAll()

    /** 一次性（非 Flow）读取全部直播源，供管理 API 等一次性任务使用 */
    suspend fun playlistSourcesOnce(): List<PlaylistSourceEntity> = playlistSourceDao.getAllOnce()

    suspend fun playlistSourceById(id: String): PlaylistSourceEntity? = playlistSourceDao.getById(id)

    /** 按归一化 URL 查找直播源，用于重复导入同源时增量合并（ADR-0004） */
    suspend fun playlistSourceByUrl(url: String): PlaylistSourceEntity? = playlistSourceDao.getByUrl(url)

    /** 按 (type, name) 查找直播源，用于文件上传重复导入同源时增量合并（ADR-0004） */
    suspend fun playlistSourceByNameAndType(name: String, type: String): PlaylistSourceEntity? =
        playlistSourceDao.getByTypeAndName(type, name)

    suspend fun upsertPlaylistSource(source: PlaylistSourceEntity) = playlistSourceDao.upsert(source)

    /**
     * 导入事务：写直播源 + 批量写频道（新增/更新/隐藏）原子完成（ADR-0004）。
     * 多表写入由 DAO 层 @Transaction 保证。
     */
    suspend fun applyImport(
        source: PlaylistSourceEntity,
        inserts: List<ChannelEntity>,
        updates: List<ChannelEntity>,
        hides: List<ChannelEntity>,
    ) = channelDao.persistImport(source, inserts, updates, hides)

    /** 删除直播源（其频道由外键级联删除，含收藏记录，ADR-0004） */
    suspend fun deletePlaylistSource(id: String) = playlistSourceDao.deleteById(id)

    // ---- EPG ----

    suspend fun upsertPrograms(programs: List<EpgProgramEntity>) = epgProgramDao.upsertAll(programs)

    fun programs(channelEpgId: String, start: Long, end: Long): Flow<List<EpgProgramEntity>> =
        epgProgramDao.getByChannelAndTime(channelEpgId, start, end)

    suspend fun deleteExpiredPrograms(threshold: Long) = epgProgramDao.deleteExpired(threshold)

    suspend fun deleteProgramsByChannelEpgIds(channelEpgIds: List<String>) =
        epgProgramDao.deleteByChannelEpgIds(channelEpgIds)

    /** 一次性（非 Flow）查询单频道窗口内节目（EPG guide 用） */
    suspend fun programsByChannelEpgIdOnce(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> = epgProgramDao.getByChannelEpgIdAndTimeOnce(channelEpgId, start, end)

    /** 一次性（非 Flow）查询多个频道窗口内节目（EPG now 用） */
    suspend fun programsByChannelEpgIdsOnce(
        channelEpgIds: List<String>,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity> = epgProgramDao.getByChannelEpgIdsAndTimeOnce(channelEpgIds, start, end)

    // ---- 应用配置 ----

    suspend fun getConfig(key: String): String? = appConfigDao.get(key)

    suspend fun putConfig(key: String, value: String) = appConfigDao.put(key, value)
}
