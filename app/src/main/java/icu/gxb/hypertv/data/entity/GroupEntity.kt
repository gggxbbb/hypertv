package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 频道分组，用于电视端列表标签页切换与 WebUI 归类管理。
 * [epgUrl]：分组级 EPG 源 URL（ticket 09，v2 新增），null = 未覆盖，回退全局源。
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val name: String,
    val orderIndex: Int,
    val isCollapsed: Boolean,
    val epgUrl: String? = null,
)
