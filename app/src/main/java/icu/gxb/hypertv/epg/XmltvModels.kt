package icu.gxb.hypertv.epg

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * XMLTV 源中的频道（`<channel id="...">`）。
 *
 * - [id]：XMLTV 频道 id，`<programme channel="...">` 引用它，也是与本地频道 epgId/tvg-id 匹配的 key
 * - [displayNames]：一个或多个 `<display-name>`，第一个为展示名，匹配器对全部名称做归一化
 * - [iconUrl]：可选 `<icon src="...">`
 */
data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String?,
)

/** XMLTV 节目单条目（`<programme ...>`），时间为已解析的 epoch millis（UTC）。 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val category: String?,
)

/** XMLTV 解析结果：频道与节目列表（均按文档出现顺序）。 */
data class XmltvParseResult(
    val channels: List<EpgChannel>,
    val programs: List<EpgProgram>,
)

/**
 * XMLTV 时间解析：`YYYYMMDDHHmmss Z`（如 `20260817000000 +0000`）→ epoch millis。
 * 带时区偏移的写法（如 `+0800`）同样支持；格式非法返回 null（调用方跳过该行）。
 */
private val XMLTV_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z", Locale.ROOT)

fun parseXmltvTime(text: String): Long? {
    return try {
        OffsetDateTime.parse(text.trim(), XMLTV_TIME_FORMAT).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
