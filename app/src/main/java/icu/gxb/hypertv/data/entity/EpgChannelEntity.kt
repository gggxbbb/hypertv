package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * EPG 频道目录（v4）：刷新成功后持久化的 XMLTV 频道（id/display-name/icon）。
 *
 * 与节目表解耦：节目过期清理/作用域清旧不删除目录，用户始终能看到「有哪些台」；
 * 目录随每次成功刷新幂等覆盖（同 id 更新 displayName/icon）。
 * 主键 id = XMLTV 频道 id（== 频道 epgId / epg_programs.channelEpgId）。
 */
@Entity(tableName = "epg_channels")
data class EpgChannelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val icon: String?,
)
