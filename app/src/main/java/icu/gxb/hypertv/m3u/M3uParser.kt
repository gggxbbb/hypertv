package icu.gxb.hypertv.m3u

/**
 * 规范化后的单条频道（导入的"新频道"）。
 *
 * 字段对应 M3U 中的约定：
 * - epgId：`#EXTINF` 的 `tvg-id` 属性，用于 EPG 匹配
 * - catchup / catchupDays / catchupSource：`catchup`、`catchup-days`、`catchup-source` 属性
 */
data class NewChannel(
    val name: String,
    val url: String,
    val groupName: String,
    val logoUrl: String?,
    val epgId: String?,
    val catchup: String?,
    val catchupDays: Int?,
    val catchupSource: String?,
)

/**
 * M3U 解析结果：规范化频道列表 + 导入预览所需的分组列表。
 * 分组按频道首次出现顺序去重，仅含非空分组名。
 */
data class M3uParseResult(
    val channels: List<NewChannel>,
    val groups: List<String>,
)

/**
 * M3U/M3U8 与国内 txt 直播源文本解析器（纯 Kotlin，JVM 可单测）。
 *
 * M3U 支持：
 * - `#EXTINF` 行属性（tvg-id/tvg-name/tvg-logo/group-title/catchup/catchup-days/catchup-source）
 * - `#EXTGRP` 分组行（优先级高于 EXTINF 的 group-title）
 * - 异常/无意义行静默跳过，不崩溃；空输入返回空列表
 * - 未带 EXTINF 的裸 URL 行按"频道 N"补全
 * - 属性值兼容带引号与不带引号两种写法，键名大小写不敏感
 *
 * txt 支持（格式探测：无 `#EXTINF` 行、且存在 `频道名,URL` 模式行时启用）：
 * - `分组名,#genre#` 行声明当前分组（不区分大小写），后续频道归入该分组
 * - `频道名,URL` 行按第一个逗号切分，右半必须带 URL scheme 才当作频道
 * - 无法识别为频道或分组行的行静默跳过
 */
class M3uParser {

    /** 频道 URL 行判定：带 URI scheme（如 http://、https://），用于跳过乱码/杂散行 */
    private val urlLine = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /** txt 直播源频道行判定：`名称,URL`，逗号后紧跟 URL scheme */
    private val txtChannelLine = Regex("^[^,]+,[A-Za-z][A-Za-z0-9+.-]*://")

    /** txt 分组行尾标记（不区分大小写） */
    private val genreMarker = "#genre#"

    fun parse(content: String): M3uParseResult {
        // 格式探测：存在 #EXTINF 行 → M3U；否则存在"名称,URL"模式行 → txt
        val isM3u = content.lineSequence().any { it.trim().startsWith("#EXTINF:", ignoreCase = true) }
        if (isM3u) return parseM3u(content)

        val isTxt = content.lineSequence().any { txtChannelLine.containsMatchIn(it.trim()) }
        if (isTxt) return parseTxt(content)

        // 两者都不是（如空内容、纯裸 URL 行）：保持历史 M3U 行为，兜底走 M3U 路径
        return parseM3u(content)
    }

    /** M3U 路径：仅处理 `#EXTINF`/`#EXTGRP`/注释/裸 URL 行，行为保持原样。 */
    private fun parseM3u(content: String): M3uParseResult {
        val channels = mutableListOf<NewChannel>()
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingName: String? = null
        var pendingGroup: String? = null
        var urlSeq = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // 属性段与标题段以"第一个不带引号的逗号"分隔（标题本身可含逗号）。
                    // 不重置 pendingGroup：#EXTGRP 可能出现在 #EXTINF 之前
                    val body = line.substringAfter(':').trim()
                    val (attrPart, title) = splitTitle(body)
                    pendingAttrs = parseAttributes(attrPart)
                    pendingName = title.takeIf { it.isNotBlank() }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#") -> {
                    // 注释/其它指令（#EXTM3U、#EXTVLCOPT、#EXT-X-* 等）忽略
                }

                urlLine.find(line) != null -> {
                    // 频道流地址行
                    urlSeq++
                    val attrs = pendingAttrs
                    channels += NewChannel(
                        name = pendingName
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "频道 $urlSeq",
                        url = line,
                        groupName = pendingGroup ?: attrs["group-title"] ?: "",
                        logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        catchup = attrs["catchup"]?.takeIf { it.isNotBlank() },
                        catchupDays = attrs["catchup-days"]?.toIntOrNull(),
                        catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
                    )
                    pendingAttrs = emptyMap()
                    pendingName = null
                    pendingGroup = null
                }

                else -> {
                    // 非 URL 的杂散行：跳过，不崩溃
                }
            }
        }

        val groups = channels.asSequence().map { it.groupName }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        return M3uParseResult(channels = channels, groups = groups)
    }

    /** txt 路径：`分组名,#genre#` 行声明分组，`频道名,URL` 行生成频道。 */
    private fun parseTxt(content: String): M3uParseResult {
        val channels = mutableListOf<NewChannel>()
        var currentGroup: String? = null

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            // 分组行：`分组名,#genre#`（#genre# 位于行尾，不区分大小写）；不产生频道
            if (line.endsWith(genreMarker, ignoreCase = true)) {
                val groupName = line
                    .substring(0, line.length - genreMarker.length)
                    .trim()
                    .removeSuffix(",")
                    .trim()
                currentGroup = groupName.takeIf { it.isNotEmpty() }
                return@forEach
            }

            // 频道行：按第一个逗号切分，右半必须带 URL scheme，防把普通文本误判
            val commaIdx = line.indexOf(',')
            if (commaIdx > 0) {
                val name = line.substring(0, commaIdx).trim()
                val url = line.substring(commaIdx + 1).trim()
                if (name.isNotEmpty() && urlLine.find(url) != null) {
                    channels += NewChannel(
                        name = name,
                        url = url,
                        groupName = currentGroup ?: "",
                        logoUrl = null,
                        epgId = null,
                        catchup = null,
                        catchupDays = null,
                        catchupSource = null,
                    )
                }
            }
            // 其余（空行/乱码/无逗号行/右半非 URL）静默跳过
        }

        val groups = channels.asSequence().map { it.groupName }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        return M3uParseResult(channels = channels, groups = groups)
    }

    /** 在第一个不带引号的逗号处把 EXTINF 内容切成 (属性段, 标题段)。 */
    private fun splitTitle(extinfBody: String): Pair<String, String> {
        var inQuote = false
        for (i in extinfBody.indices) {
            when (extinfBody[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) {
                    return extinfBody.substring(0, i) to extinfBody.substring(i + 1).trim()
                }
            }
        }
        // 无逗号：整个当作属性段，标题为空
        return extinfBody to ""
    }

    /** 解析属性段为键值表，键统一小写；支持 `k="v"` 与 `k=v` 两种写法。 */
    private fun parseAttributes(attrPart: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        val len = attrPart.length
        while (i < len) {
            while (i < len && attrPart[i].isWhitespace()) i++
            if (i >= len) break

            val keyStart = i
            while (i < len && attrPart[i] != '=' && !attrPart[i].isWhitespace()) i++
            val key = attrPart.substring(keyStart, i).lowercase()
            while (i < len && attrPart[i].isWhitespace()) i++
            if (i >= len || attrPart[i] != '=') continue // 畸形属性，跳过
            i++
            while (i < len && attrPart[i].isWhitespace()) i++

            val value: String
            if (i < len && attrPart[i] == '"') {
                i++
                val valueStart = i
                while (i < len && attrPart[i] != '"') i++
                value = attrPart.substring(valueStart, i)
                if (i < len) i++ // 跳过收尾引号
            } else {
                val valueStart = i
                while (i < len && !attrPart[i].isWhitespace()) i++
                value = attrPart.substring(valueStart, i)
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
