package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 频道：单个可播放的直播流，归属于一个直播源与一个分组。
 *
 * 频道号（Channel Number）= 全局 orderIndex + 1，与分组无关。
 *
 * - sourceId 外键级联：删除直播源会连带删除其频道（ADR-0004）
 * - url 单列索引：增量合并按频道 URL 匹配（ADR-0004）
 * - sourceId 索引：外键子列要求 + getBySourceId 查询加速
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("url"), Index("sourceId")],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val url: String,
    val groupName: String,
    val logoUrl: String?,
    /** 全局稳定排序位置，频道号 = orderIndex + 1 */
    val orderIndex: Int,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    /** 用于 EPG 匹配的 tvg-id */
    val epgId: String?,
    val catchup: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
    val createdAt: Long,
)
