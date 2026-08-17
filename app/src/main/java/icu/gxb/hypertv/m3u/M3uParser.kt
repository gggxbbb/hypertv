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
 * M3U/M3U8 文本解析器（纯 Kotlin，JVM 可单测）。
 *
 * 支持：
 * - `#EXTINF` 行属性（tvg-id/tvg-name/tvg-logo/group-title/catchup/catchup-days/catchup-source）
 * - `#EXTGRP` 分组行（优先级高于 EXTINF 的 group-title）
 * - 异常/无意义行静默跳过，不崩溃；空输入返回空列表
 * - 未带 EXTINF 的裸 URL 行按"频道 N"补全
 * - 属性值兼容带引号与不带引号两种写法，键名大小写不敏感
 */
class M3uParser {

    /** 频道 URL 行判定：带 URI scheme（如 http://、https://），用于跳过乱码/杂散行 */
    private val urlLine = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun parse(content: String): M3uParseResult {
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
