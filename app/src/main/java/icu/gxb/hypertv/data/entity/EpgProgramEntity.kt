package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EPG 节目单条目，通过 channelEpgId 与频道关联（不设外键，EPG 数据独立于频道生命周期）。
 * (channelEpgId, startTime) 复合索引：节目单查询与过期清理加速。
 */
@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["channelEpgId", "startTime"])],
)
data class EpgProgramEntity(
    @PrimaryKey val id: String,
    val channelEpgId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val category: String?,
)
