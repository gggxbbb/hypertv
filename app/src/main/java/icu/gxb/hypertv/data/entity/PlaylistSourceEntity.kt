package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 直播源（M3U/M3U8），可以是 URL 或上传的文件。
 * 删除直播源时，channels 表通过外键 CASCADE 级联删除其全部频道（ADR-0004）。
 */
@Entity(tableName = "playlist_sources")
data class PlaylistSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "url" 或 "file" */
    val type: String,
    val url: String,
    val lastImportedAt: Long,
    val createdAt: Long,
)
