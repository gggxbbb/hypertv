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

/** EPG 频道关键字匹配规则（纯模型，无自增 id；数据库实体见 EpgMatchRuleEntity）。 */
data class MatchRule(
    val epgChannelId: String,
    val keyword: String,
    /** "prefix" = 频道名以 keyword 开头；"contains" = 频道名包含 keyword */
    val ruleType: String,
)

/** 规则应用结果：待写回的 (channelId, epgId) 列表 + 每条规则命中频道数（与入参 rules 同序）。 */
data class MatchRuleApplicationResult(
    val updates: List<Pair<String, String>>,
    val hits: List<Int>,
) {
    /** 实际命中（产生更新）的频道数 */
    val appliedCount: Int get() = updates.size
}

/** 规则类型常量（与数据库存值一致）。 */
object MatchRuleType {
    const val PREFIX = "prefix"
    const val CONTAINS = "contains"
}

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

/**
 * 应用关键字匹配规则（纯函数，JVM 可单测）。
 *
 * - 只处理 **epgId 为 null 且 epgManual=false** 的频道：已有 epgId（三级自动匹配过）
 *   与手动绑定的频道一律不碰
 * - 频道名与关键字按大小写不敏感比较（全小写）；prefix = 以 keyword 开头，
 *   contains = 包含 keyword
 * - 同频道命中多条规则时取第一条（入参顺序，确定性）；一条规则可命中多个频道
 *   （如关键字 "CCTV-1" 命中 CCTV-1 / CCTV-1HD / CCTV-1标清 等多个清晰度源）
 *
 * @return 待写回更新（调用方经 EpgStore.updateChannelEpgIds 落库，epgManual 保持不变）
 */
fun applyMatchRules(
    channels: List<ChannelEntity>,
    rules: List<MatchRule>,
): MatchRuleApplicationResult {
    if (rules.isEmpty()) return MatchRuleApplicationResult(emptyList(), List(rules.size) { 0 })
    val hits = IntArray(rules.size)
    val updates = ArrayList<Pair<String, String>>()
    for (channel in channels) {
        val hasEpgId = channel.epgId?.trim()?.isNotEmpty() == true
        if (hasEpgId || channel.epgManual) continue
        val ruleIndex = rules.indexOfFirst { rule ->
            val keyword = rule.keyword.trim().lowercase(Locale.ROOT)
            if (keyword.isEmpty()) return@indexOfFirst false
            val name = channel.name.lowercase(Locale.ROOT)
            when (rule.ruleType) {
                MatchRuleType.PREFIX -> name.startsWith(keyword)
                MatchRuleType.CONTAINS -> name.contains(keyword)
                else -> false
            }
        }
        if (ruleIndex < 0) continue
        hits[ruleIndex]++
        updates += channel.id to rules[ruleIndex].epgChannelId.trim()
    }
    return MatchRuleApplicationResult(updates = updates, hits = hits.toList())
}
