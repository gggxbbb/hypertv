package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import java.util.Locale

/** 三级匹配级别（数值越小优先级越高，spec「EPG 匹配三级策略」）。 */
enum class EpgMatchLevel(val priority: Int) {
    /** 1. tvg-id 精确匹配（XMLTV id == 频道 epgId） */
    EXACT_TVG_ID(1),

    /** 2. tvg-id 忽略大小写匹配 */
    CASE_INSENSITIVE_TVG_ID(2),

    /** 3. 频道名归一化精确匹配（XMLTV display-name vs 频道 name） */
    NORMALIZED_NAME(3),
}

/** 匹配统计：总频道数、匹配数与各级命中数（供 WebUI 展示命中率）。 */
data class EpgMatchStats(
    val total: Int,
    val matched: Int,
    val level1: Int,
    val level2: Int,
    val level3: Int,
) {
    val unmatched: Int get() = total - matched

    /** 命中率（0~1），无频道时为 1。 */
    val rate: Double get() = if (total == 0) 1.0 else matched.toDouble() / total
}

/** 匹配结果：channelId → xmltvId 映射 + 各级别明细 + 统计。 */
data class EpgMatchResult(
    val mapping: Map<String, String>,
    val levels: Map<String, EpgMatchLevel>,
    val stats: EpgMatchStats,
)

/**
 * 三级频道匹配器（纯函数，JVM 可单测）。
 *
 * 规则（spec「EPG 匹配」）：
 * 1. 频道 epgId 与 XMLTV id 精确相等 → 最高优先级
 * 2. 忽略大小写相等 → 次优先
 * 3. 频道名与 XMLTV display-name 归一化后相等 → 最低优先（用于无 tvg-id 或 id 对不上的频道）
 *
 * 不做模糊评分（Levenshtein 等）；未匹配频道不进入映射（EPG 数据不关联）。
 * 多个 XMLTV 候选命中同一频道时取第一个（确定性），同源列表顺序稳定。
 */
class EpgChannelMatcher {

    fun match(xmltvChannels: List<EpgChannel>, channels: List<ChannelEntity>): EpgMatchResult {
        // XMLTV 侧索引：精确 id / 小写 id / 归一化 display-name（同名时首个候选胜出）
        val exactByEpgId = HashMap<String, String>()
        val ciByEpgId = HashMap<String, String>()
        val byNormalizedName = HashMap<String, String>()
        for (xc in xmltvChannels) {
            if (xc.id.isEmpty()) continue
            exactByEpgId.putIfAbsent(xc.id, xc.id)
            ciByEpgId.putIfAbsent(xc.id.lowercase(Locale.ROOT), xc.id)
            for (name in xc.displayNames) {
                val norm = normalizeName(name)
                if (norm.isNotEmpty()) byNormalizedName.putIfAbsent(norm, xc.id)
            }
        }

        val mapping = HashMap<String, String>()
        val levels = HashMap<String, EpgMatchLevel>()
        var level1 = 0
        var level2 = 0
        var level3 = 0

        for (channel in channels) {
            val match = matchChannel(channel, exactByEpgId, ciByEpgId, byNormalizedName) ?: continue
            mapping[channel.id] = match.xmltvId
            levels[channel.id] = match.level
            when (match.level) {
                EpgMatchLevel.EXACT_TVG_ID -> level1++
                EpgMatchLevel.CASE_INSENSITIVE_TVG_ID -> level2++
                EpgMatchLevel.NORMALIZED_NAME -> level3++
            }
        }

        return EpgMatchResult(
            mapping = mapping,
            levels = levels,
            stats = EpgMatchStats(total = channels.size, matched = mapping.size, level1 = level1, level2 = level2, level3 = level3),
        )
    }

    private fun matchChannel(
        channel: ChannelEntity,
        exactByEpgId: Map<String, String>,
        ciByEpgId: Map<String, String>,
        byNormalizedName: Map<String, String>,
    ): Matched? {
        val epgId = channel.epgId?.trim()?.takeIf { it.isNotEmpty() }
        if (epgId != null) {
            exactByEpgId[epgId]?.let { return Matched(it, EpgMatchLevel.EXACT_TVG_ID) }
            ciByEpgId[epgId.lowercase(Locale.ROOT)]?.let { return Matched(it, EpgMatchLevel.CASE_INSENSITIVE_TVG_ID) }
        }
        val norm = normalizeName(channel.name)
        if (norm.isEmpty()) return null
        val xmltvId = byNormalizedName[norm] ?: return null
        return Matched(xmltvId, EpgMatchLevel.NORMALIZED_NAME)
    }

    private data class Matched(val xmltvId: String, val level: EpgMatchLevel)

    companion object {
        /**
         * 频道名归一化：全角 → 半角 → 小写 → 仅保留字母与数字（去空白、去标点）。
         * 中文字符保留（isLetterOrDigit 对 CJK 为 true）。
         */
        fun normalizeName(raw: String): String = buildString(raw.length) {
            for (ch in raw) {
                val half = when {
                    ch == '\u3000' -> ' '
                    ch in '\uFF01'..'\uFF5E' -> ch - 0xFEE0
                    else -> ch
                }
                if (half.isLetterOrDigit()) append(half.lowercaseChar())
            }
        }
    }
}
