package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 频道分组，用于电视端列表标签页切换与 WebUI 归类管理。
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val name: String,
    val orderIndex: Int,
    val isCollapsed: Boolean,
)
