package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity

/**
 * 导入流程所需的数据层入口（Repository 的窄接口）。
 *
 * 独立成接口以便 Ktor 路由单测注入内存实现/fake；真实实现见
 * [HypertvPlaylistImportStore]，由 [HypertvRepository] 适配。
 */
interface PlaylistImportStore {

    /** 按归一化 URL 查找已有直播源（重复导入同一 URL 时复用其 id 做增量合并） */
    suspend fun sourceByUrl(url: String): PlaylistSourceEntity?

    suspend fun upsertSource(source: PlaylistSourceEntity)

    /** 一次性读取某直播源全部频道（增量合并的"旧频道集"） */
    suspend fun channelsBySource(sourceId: String): List<ChannelEntity>

    /** 事务性落库：写直播源 + 批量写频道（新增/更新/隐藏） */
    suspend fun applyImport(
        source: PlaylistSourceEntity,
        inserts: List<ChannelEntity>,
        updates: List<ChannelEntity>,
        hides: List<ChannelEntity>,
    )
}
