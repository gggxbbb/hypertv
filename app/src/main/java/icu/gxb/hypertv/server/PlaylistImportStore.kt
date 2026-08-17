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

    /** 全部直播源（按 createdAt 升序），供管理 API 列表展示 */
    suspend fun sources(): List<PlaylistSourceEntity>

    /** 按 id 一次性读取（不存在返回 null） */
    suspend fun sourceById(id: String): PlaylistSourceEntity?

    /** 按归一化 URL 查找已有直播源（重复导入同一 URL 时复用其 id 做增量合并） */
    suspend fun sourceByUrl(url: String): PlaylistSourceEntity?

    /** 按 (type, name) 查找直播源（文件上传重复导入同源时复用其 id 做增量合并） */
    suspend fun sourceByNameAndType(name: String, type: String): PlaylistSourceEntity?

    suspend fun upsertSource(source: PlaylistSourceEntity)

    /** 删除直播源，其全部频道（含收藏记录）经外键级联删除（ADR-0004） */
    suspend fun deleteSource(id: String)

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
