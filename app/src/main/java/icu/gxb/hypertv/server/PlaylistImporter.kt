package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import icu.gxb.hypertv.m3u.EncodingDetector
import icu.gxb.hypertv.m3u.M3uParser
import icu.gxb.hypertv.m3u.mergeChannels
import icu.gxb.hypertv.m3u.normalizeUrl
import java.util.UUID

/** 预览响应的频道条数上限（WebUI 仅展示前若干条，总数见 total）。 */
private const val PREVIEW_LIMIT = 100

/**
 * 导入编排：URL 拉取 / 文件字节 → 编码识别 → M3U 解析 → 预览（不落库）或
 * 增量合并落库（ADR-0004）。
 *
 * 纯逻辑依赖（parser/detector/store/拉取函数）全部由参数注入，可独立单测。
 */
class PlaylistImporter(
    private val parser: M3uParser,
    private val encodingDetector: EncodingDetector,
    private val store: PlaylistImportStore,
    private val fetchUrl: suspend (String) -> ByteArray,
) {

    /** 解析预览（URL 源，不落库）。 */
    suspend fun previewUrl(url: String): ImportPreview {
        val bytes = fetchUrl(url)
        return buildPreview(bytes, sourceName = sourceNameFromUrl(url), sourceUrl = url)
    }

    /** 解析预览（上传文件，不落库）。 */
    suspend fun previewBytes(fileName: String?, bytes: ByteArray): ImportPreview =
        buildPreview(bytes, sourceName = fileName ?: "未命名源", sourceUrl = null)

    /** 确认导入（URL 源）：重复导入同一 URL 复用其直播源做增量合并。 */
    suspend fun importUrl(url: String): ImportResult {
        val bytes = fetchUrl(url)
        return importContent(sourceUrl = url, sourceName = sourceNameFromUrl(url), bytes = bytes)
    }

    /** 确认导入（上传文件）：每次上传视为新直播源（URL 为空无法判定同源）。 */
    suspend fun importBytes(fileName: String?, bytes: ByteArray): ImportResult =
        importContent(sourceUrl = null, sourceName = fileName ?: "未命名源", bytes = bytes)

    // ---- 内部 ----

    private fun buildPreview(bytes: ByteArray, sourceName: String, sourceUrl: String?): ImportPreview {
        val decoded = encodingDetector.decodeDetected(bytes)
        val parsed = parser.parse(decoded.text)
        return ImportPreview(
            total = parsed.channels.size,
            groups = parsed.groups,
            preview = parsed.channels.take(PREVIEW_LIMIT).map {
                ChannelPreview(
                    name = it.name,
                    url = it.url,
                    groupName = it.groupName,
                    logoUrl = it.logoUrl,
                    epgId = it.epgId,
                )
            },
            encoding = decoded.encoding,
            sourceName = sourceName,
            url = sourceUrl,
        )
    }

    private suspend fun importContent(sourceUrl: String?, sourceName: String, bytes: ByteArray): ImportResult {
        val decoded = encodingDetector.decodeDetected(bytes)
        val parsed = parser.parse(decoded.text)
        val now = System.currentTimeMillis()

        // 直播源：URL 导入按归一化 URL 找已有源复用（增量合并）；文件导入新建
        val source = resolveSource(sourceUrl, sourceName, now)
        val existing = store.channelsBySource(source.id)
        val merge = mergeChannels(existing, parsed.channels, source.id, now)
        store.applyImport(source, merge.inserts, merge.updates, merge.hides)

        return ImportResult(
            imported = merge.imported,
            updated = merge.updated,
            hidden = merge.hidden,
            sourceId = source.id,
        )
    }

    private suspend fun resolveSource(
        sourceUrl: String?,
        sourceName: String,
        now: Long,
    ): PlaylistSourceEntity {
        val normalizedUrl = sourceUrl?.let(::normalizeUrl)
        val existing = normalizedUrl?.let { store.sourceByUrl(it) }
        return if (existing != null) {
            // 复用已有直播源：保留用户重命名，仅刷新 lastImportedAt
            existing.copy(lastImportedAt = now)
        } else {
            PlaylistSourceEntity(
                id = UUID.randomUUID().toString(),
                name = sourceName.ifBlank { "未命名源" },
                type = if (sourceUrl != null) "url" else "file",
                url = normalizedUrl ?: "",
                lastImportedAt = now,
                createdAt = now,
            )
        }
    }
}
