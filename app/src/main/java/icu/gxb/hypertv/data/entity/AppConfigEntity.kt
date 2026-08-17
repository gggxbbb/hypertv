package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用键值配置（如 EPG 数据源地址、上次成功刷新时间）。
 */
@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String,
)
