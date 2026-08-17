package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 全局 EPG 源（v3 多源）：App 可同时配置多个 XMLTV 源，全部启用源拉取后合并节目。
 * 顺序由 [orderIndex] 决定；合并冲突（同 EPG 频道同开始时间）时按拉取顺序后源胜出。
 */
@Entity(
    tableName = "epg_sources",
    indices = [Index(value = ["url"], unique = true)],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val enabled: Boolean = true,
    val orderIndex: Int,
)
