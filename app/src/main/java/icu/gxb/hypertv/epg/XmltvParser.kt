package icu.gxb.hypertv.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

/**
 * XMLTV 流式解析器（纯 Kotlin，JVM 可单测）。
 *
 * 用 XmlPullParser 流式遍历，避免 DOM 全量加载：万条节目解析耗时远低于 5s。
 *
 * 容错策略：
 * - 缺少 id/channel/start/stop、时间格式非法、无标题的条目静默跳过，不崩溃
 * - 文档中途畸形（非法 XML）时保留已解析部分，不抛异常给调用方
 * - 子元素（display-name/title/desc/category）内的异常内容单独吞掉
 *
 * 编码（UTF-8/GBK）由调用方先用 EncodingDetector 解码为 String 再传入。
 */
class XmltvParser {

    fun parse(content: String): XmltvParseResult {
        val channels = mutableListOf<EpgChannel>()
        val programs = mutableListOf<EpgProgram>()
        val parser = newPullParser(content)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        // 根容器 <tv>：不消费子树，交由外层循环遍历其子元素
                        "tv" -> {}
                        "channel" -> parseChannel(parser)?.let(channels::add)
                        "programme" -> parseProgramme(parser)?.let(programs::add)
                        else -> parser.skipToEnd(parser.name)
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            // 畸形/截断的 XML：保留已解析部分，不崩溃
        } catch (_: IOException) {
            // StringReader 不会真实 IO 失败；兜底保护
        }
        return XmltvParseResult(channels = channels, programs = programs)
    }

    private fun newPullParser(content: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(content))
        return parser
    }

    /** 解析 `<channel id="...">` 子树，收集 display-name/icon；id 缺失返回 null。 */
    private fun parseChannel(parser: XmlPullParser): EpgChannel? {
        val id = parser.getAttributeValue(null, ATTRIBUTE_ID)?.trim().orEmpty()
        val names = mutableListOf<String>()
        var icon: String? = null
        parser.consumeChildren("channel") { tag ->
            when (tag) {
                "display-name" -> safeNextText(parser)?.let { text ->
                    text.trim().takeIf { it.isNotEmpty() }?.let(names::add)
                }
                "icon" -> icon = parser.getAttributeValue(null, "src")?.takeIf { it.isNotBlank() } ?: icon
            }
        }
        if (id.isEmpty()) return null
        return EpgChannel(id = id, displayNames = names, iconUrl = icon)
    }

    /** 解析 `<programme ...>` 子树；channel/start/stop 缺失或时间非法、无标题时返回 null。 */
    private fun parseProgramme(parser: XmlPullParser): EpgProgram? {
        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start").orEmpty())
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop").orEmpty())
        var title: String? = null
        var description: String? = null
        var category: String? = null
        parser.consumeChildren("programme") { tag ->
            when (tag) {
                "title" -> title = safeNextText(parser)?.trim()?.takeIf { it.isNotEmpty() } ?: title
                "desc" -> description = safeNextText(parser)?.trim() ?: description
                "category" -> category = safeNextText(parser)?.trim() ?: category
            }
        }
        if (channelId.isEmpty() || start == null || stop == null) return null
        val resolvedTitle = title?.takeIf { it.isNotEmpty() } ?: return null
        return EpgProgram(
            channelId = channelId,
            title = resolvedTitle,
            description = description,
            startTime = start,
            endTime = stop,
            category = category,
        )
    }

    /** 读取文本子元素内容；元素为空或内容畸形时返回 null（继续后续遍历）。 */
    private fun safeNextText(parser: XmlPullParser): String? {
        return try {
            parser.nextText()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从当前 START_TAG 之后的第一个事件开始，遍历到与 [parent] 匹配的 END_TAG（depth 归零）。
     * 遇到同名子元素时 depth 递增（防御嵌套）；遇到文档结束立即返回（防畸形死循环）。
     */
    private fun XmlPullParser.consumeChildren(parent: String, onStart: (String) -> Unit) {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> {
                    if (name == parent) depth++ else onStart(name)
                }
                XmlPullParser.END_TAG -> if (name == parent) depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** 跳过当前元素整棵子树（不解析内容）。 */
    private fun XmlPullParser.skipToEnd(parent: String) {
        consumeChildren(parent) {}
    }

    private companion object {
        const val ATTRIBUTE_ID = "id"
    }
}
