package icu.gxb.hypertv.data.entity

/**
 * 频道 EPG 匹配回写（v5）：一次携带 epgId 与来源（"manual" | "rule" | "level1"~"level5"），
 * 供 EPG 刷新（自动匹配/规则）与规则应用落库，保证 epgId 与来源原子一致。
 */
data class ChannelEpgMatchUpdate(
    val channelId: String,
    val epgId: String,
    val source: String,
)
