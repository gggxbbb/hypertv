package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import java.util.Locale

/** 匹配级别（数值越小优先级越高，spec「EPG 匹配三级策略」+ v4 前缀匹配 + v5 包含匹配）。 */
enum class EpgMatchLevel(val priority: Int) {
    /** 1. tvg-id 精确匹配（XMLTV id == 频道 epgId） */
    EXACT_TVG_ID(1),

    /** 2. tvg-id 忽略大小写匹配 */
    CASE_INSENSITIVE_TVG_ID(2),

    /** 3. 频道名归一化精确匹配（XMLTV display-name vs 频道 name） */
    NORMALIZED_NAME(3),

    /**
     * 4. 频道名归一化前缀匹配：本地频道名归一化后以 XMLTV display-name 归一化结果开头。
     * 边界检查（防 CCTV-1 误配 CCTV-10/11/12/13）+ 清晰度后缀白名单（4k/8k/hd/uhd/超清…），
     * 多个前缀候选命中时取 display-name 更长者。
     */
    NORMALIZED_PREFIX(4),

    /**
     * 5. 频道名归一化包含匹配：本地频道名归一化后包含 XMLTV display-name 归一化结果
     * （子串，任意位置，如 `CCTV-风云剧场` → `风云剧场`）。仅在前四级未命中时生效；
     * 候选归一化长度须 >= [EpgChannelMatcher.CONTAINS_MIN_LENGTH]（防 "tv"/"卫视"/"cctv"
     * 等短通用名误配），多个包含命中时取 display-name 更长者。
     */
    NORMALIZED_CONTAINS(5),
}

/** 匹配统计：总频道数、匹配数与各级命中数（供 WebUI 展示命中率）。 */
data class EpgMatchStats(
    val total: Int,
    val matched: Int,
    val level1: Int,
    val level2: Int,
    val level3: Int,
    val level4: Int = 0,
    val level5: Int = 0,
) {
    val unmatched: Int get() = total - matched

    /** 命中率（0~1），无频道时为 1。 */
    val rate: Double get() = if (total == 0) 1.0 else matched.toDouble() / total
}

/** epgMatchSource 列取值（v5）：手动绑定 / 规则命中 / 自动匹配级别。 */
object EpgMatchSource {
    const val MANUAL = "manual"
    const val RULE = "rule"

    /** 自动匹配级别来源（"level1"~"level5"），与 [EpgMatchLevel] 的 priority 对应。 */
    fun level(level: EpgMatchLevel): String = "level${level.priority}"
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
 * 三级匹配器升级为五级（纯函数，JVM 可单测）。
 *
 * 规则（spec「EPG 匹配」+ v4 前缀匹配 + v5 包含匹配）：
 * 1. 频道 epgId 与 XMLTV id 精确相等 → 最高优先级
 * 2. 忽略大小写相等 → 次优先
 * 3. 频道名与 XMLTV display-name 归一化后相等 → 次低优先（用于无 tvg-id 或 id 对不上的频道）
 * 4. 频道名归一化后以 display-name 归一化结果开头 → 低优先：
 *    - 前缀后紧跟字符若是汉字/空白/标点（归一化后仅剩汉字）则接受
 *      （如 `CCTV-2 财经` → `CCTV2`，后邻「财」）
 *    - 若紧邻是 ASCII 字母/数字，则剩余部分必须整体由清晰度白名单构成或以其开头
 *      （如 `4k`/`8k`/`hd`/`uhd`/`超清`/`高清`/`超高清`/`标清` 及组合 `4k超高清`），
 *      否则拒绝（防 `CCTV-1` 误配 `CCTV-10/11/12/13`）
 *    - 多个前缀候选命中同一频道时取 display-name 更长者（如 `cctv4k` 优先于 `cctv4`）
 * 5. 频道名归一化包含 display-name 归一化结果（子串，任意位置）→ 最低优先：
 *    - 长度阈值：候选归一化长度 >= 4（排除 "tv"/"卫视"/"cctv" 等短通用名；中文 2 字
 *      台名同样被排除——可接受，用户用规则补）
 *    - 多个包含候选命中时取 display-name 更长者；同长取源顺序先出现者（稳定排序）
 *    - 只对前四级未命中的频道生效（前缀命中优先于包含命中：`CCTV-风云剧场` 若 EPG
 *      同时有 `CCTV` 与 `风云剧场`，level4 先命中 `CCTV`——已知取舍，想绑风云剧场用规则）
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
        // 归一化 display-name 候选（去重后按长度降序，最长匹配优先）；前缀与包含共用，
        // 包含级额外过滤长度阈值（见 [CONTAINS_MIN_LENGTH]）
        val candidates = ArrayList<Pair<String, String>>()
        val seenCandidates = HashSet<String>()
        for (xc in xmltvChannels) {
            if (xc.id.isEmpty()) continue
            exactByEpgId.putIfAbsent(xc.id, xc.id)
            ciByEpgId.putIfAbsent(xc.id.lowercase(Locale.ROOT), xc.id)
            for (name in xc.displayNames) {
                val norm = normalizeName(name)
                if (norm.isEmpty()) continue
                byNormalizedName.putIfAbsent(norm, xc.id)
                if (seenCandidates.add(norm)) candidates += norm to xc.id
            }
        }
        candidates.sortByDescending { it.first.length }
        val containsCandidates = candidates.filter { it.first.length >= CONTAINS_MIN_LENGTH }

        val mapping = HashMap<String, String>()
        val levels = HashMap<String, EpgMatchLevel>()
        var level1 = 0
        var level2 = 0
        var level3 = 0
        var level4 = 0
        var level5 = 0

        for (channel in channels) {
            val match = matchChannel(channel, exactByEpgId, ciByEpgId, byNormalizedName, candidates, containsCandidates)
                ?: continue
            mapping[channel.id] = match.xmltvId
            levels[channel.id] = match.level
            when (match.level) {
                EpgMatchLevel.EXACT_TVG_ID -> level1++
                EpgMatchLevel.CASE_INSENSITIVE_TVG_ID -> level2++
                EpgMatchLevel.NORMALIZED_NAME -> level3++
                EpgMatchLevel.NORMALIZED_PREFIX -> level4++
                EpgMatchLevel.NORMALIZED_CONTAINS -> level5++
            }
        }

        return EpgMatchResult(
            mapping = mapping,
            levels = levels,
            stats = EpgMatchStats(
                total = channels.size,
                matched = mapping.size,
                level1 = level1,
                level2 = level2,
                level3 = level3,
                level4 = level4,
                level5 = level5,
            ),
        )
    }

    private fun matchChannel(
        channel: ChannelEntity,
        exactByEpgId: Map<String, String>,
        ciByEpgId: Map<String, String>,
        byNormalizedName: Map<String, String>,
        prefixCandidates: List<Pair<String, String>>,
        containsCandidates: List<Pair<String, String>>,
    ): Matched? {
        val epgId = channel.epgId?.trim()?.takeIf { it.isNotEmpty() }
        if (epgId != null) {
            exactByEpgId[epgId]?.let { return Matched(it, EpgMatchLevel.EXACT_TVG_ID) }
            ciByEpgId[epgId.lowercase(Locale.ROOT)]?.let { return Matched(it, EpgMatchLevel.CASE_INSENSITIVE_TVG_ID) }
        }
        val norm = normalizeName(channel.name)
        if (norm.isEmpty()) return null
        byNormalizedName[norm]?.let { return Matched(it, EpgMatchLevel.NORMALIZED_NAME) }
        // 第四级：归一化前缀匹配。候选已按归一化 display-name 长度降序 → 首个命中即最长。
        for ((prefix, xmltvId) in prefixCandidates) {
            if (prefix.length >= norm.length) continue
            if (!norm.startsWith(prefix)) continue
            if (isPrefixBoundaryAcceptable(norm.substring(prefix.length))) {
                return Matched(xmltvId, EpgMatchLevel.NORMALIZED_PREFIX)
            }
        }
        // 第五级：归一化包含匹配。候选已过滤长度阈值并按长度降序 → 首个命中即最长；
        // 仅当前四级全部未命中时执行（前缀命中优先于包含命中，见类注释中的取舍）。
        for ((candidate, xmltvId) in containsCandidates) {
            if (candidate.length >= norm.length) continue
            if (!containsAtAcceptableBoundary(norm, candidate)) continue
            return Matched(xmltvId, EpgMatchLevel.NORMALIZED_CONTAINS)
        }
        return null
    }

    /**
     * 包含匹配的边界检查：候选归一化串在频道名中出现的位置是否可接受。
     * - 纯 CJK 候选（品牌前缀/中缀场景，如 `CCTV-风云剧场` 内的 `风云剧场`）：任意邻接都接受
     * - ASCII 候选：
     *   - 前邻须为非 ASCII 或串首（防 "xstarsports" 之类字母前缀误配 "starsports"）
     *   - 后邻须为非 ASCII 或串尾；唯一例外是「候选不以数字结尾 + 后邻为纯数字」
     *     （频道编号后缀，如 `StarSports 1` → `starsports` + "1"）
     *   - 候选以数字结尾时数字后缀是在续数（防 `cctv1` 在 `cctv10`/`cctv11`/`cctv1x` 内误配，
     *     与第四级边界检查目的一致）
     * 遍历候选在频道名中的全部出现位置，任一位置可接受即命中。
     */
    private fun containsAtAcceptableBoundary(norm: String, candidate: String): Boolean {
        var idx = norm.indexOf(candidate)
        while (idx >= 0) {
            if (isContainsBoundaryAcceptable(norm, candidate, idx)) return true
            idx = norm.indexOf(candidate, idx + 1)
        }
        return false
    }

    private fun isContainsBoundaryAcceptable(norm: String, candidate: String, start: Int): Boolean {
        val before = if (start > 0) norm[start - 1] else null
        val after = if (start + candidate.length < norm.length) norm[start + candidate.length] else null
        if (candidate.none { isAsciiLetterOrDigit(it) }) return true
        if (before != null && isAsciiLetterOrDigit(before)) return false
        val afterAscii = after != null && isAsciiLetterOrDigit(after)
        if (!afterAscii) return true
        return isAsciiDigit(after) && !isAsciiDigit(candidate.last())
    }

    /**
     * 前缀边界检查：判断本地频道名中紧跟前缀的剩余部分是否可接受。
     * 归一化后剩余部分只可能含 ASCII 字母/数字与汉字：
     * - 空 → 接受（完整前缀）
     * - 汉字开头 → 接受（台名后缀，如「财经」）
     * - ASCII 字母/数字开头 → 必须由清晰度白名单构成或以其开头（防 CCTV-1 误配 CCTV-10）
     */
    private fun isPrefixBoundaryAcceptable(remainder: String): Boolean {
        if (remainder.isEmpty()) return true
        val first = remainder[0]
        if (isAsciiLetterOrDigit(first)) return isWhitelistComposed(remainder)
        return true
    }

    /**
     * 清晰度后缀白名单判定：剩余部分整体由白名单项构成，或以其开头且后续仅为汉字。
     * 支持组合（如 `4k超高清` = `4k` + `超高清`），贪心按长 token 优先剥离。
     */
    private fun isWhitelistComposed(remainder: String): Boolean {
        var rest = remainder
        var matched = false
        while (rest.isNotEmpty()) {
            val token = RESOLUTION_WHITELIST.firstOrNull { rest.startsWith(it) } ?: break
            matched = true
            rest = rest.substring(token.length)
        }
        if (!matched) return false
        // 剥离白名单后的剩余部分只能为汉字（台名后缀，如 `4k财经`）
        return rest.isEmpty() || rest.all { isCjk(it) }
    }

    private fun isAsciiLetterOrDigit(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    private fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

    private fun isCjk(ch: Char): Boolean =
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN

    private data class Matched(val xmltvId: String, val level: EpgMatchLevel)

    companion object {
        /**
         * 第五级包含匹配的最小候选长度：EPG display-name 归一化长度须 >= 4 才参与包含匹配。
         * 排除 "tv"/"卫视"/"cctv" 这类短通用名在任意位置子串下的误配；中文 2 字台名
         * （如「综合」）长度 2 同样被排除——可接受，用户用匹配规则补。
         */
        const val CONTAINS_MIN_LENGTH = 4

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

        /** 清晰度后缀白名单（长 token 在前，保证贪心组合匹配如 `超高清` 优先于 `超清`）。 */
        private val RESOLUTION_WHITELIST = listOf("4k", "8k", "uhd", "hd", "超高清", "超清", "高清", "标清")
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
 * @return 待写回更新（调用方经 EpgStore.updateChannelEpgMatches 落库，epgManual 保持不变，
 *   来源写 "rule"）
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
