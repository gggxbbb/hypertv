package icu.gxb.hypertv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EPG 频道关键字匹配规则（v3 手动匹配）：按频道名（大小写不敏感）prefix/contains 命中，
 * 把命中频道绑定到 [epgChannelId]。规则命中优先级高于三级自动匹配，但不覆盖 epgManual=true 的频道。
 */
@Entity(
    tableName = "epg_match_rules",
    indices = [Index(value = ["epgChannelId"])],
)
data class EpgMatchRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 目标 EPG 频道 id（来自 XMLTV，对应 epg_programs.channelEpgId） */
    val epgChannelId: String,
    /** 匹配关键字 */
    val keyword: String,
    /** "prefix" = 频道名以 keyword 开头；"contains" = 频道名包含 keyword */
    val ruleType: String,
)
