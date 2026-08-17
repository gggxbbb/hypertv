package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import icu.gxb.hypertv.m3u.EncodingDetector
import icu.gxb.hypertv.m3u.M3uParser
import icu.gxb.hypertv.m3u.mergeChannels
import icu.gxb.hypertv.m3u.normalizeUrl
import java.util.UUID

/** 预览响应的频道条数上限（WebUI 仅展示前若干条，总数见 total）。 */
private const val PREVIEW_LIMIT = 100

/** 预览时的增量合并预测（同源已存在时计算，不落库）。 */
private data class IncrementalPrediction(
    val imported: Int,
    val updated: Int,
    val hidden: Int,
    val existingCount: Int,
)

/**
 * 导入编排：URL 拉取 / 文件字节 → 编码识别 → M3U 解析 → 预览（不落库）或
 * 增量合并落库（ADR-0004）。
 *
 * 纯逻辑依赖（parser/detector/store/拉取函数/文件读写）全部由参数注入，可独立单测。
 *
 * 文件型源（type=file）：
 * - url 字段保存文件内容的落盘路径（由 [saveFile] 写入），refresh 时用 [readFile] 读回；
 *   若路径对应文件不存在则报错（路由层转 400）
 * - 上传时若提供 sourceName，按 (type=file, name) 匹配已有文件源，匹配到则复用其 id
 *   做增量合并（等同 refresh），否则新建
 */
class PlaylistImporter(
    private val parser: M3uParser,
    private val encodingDetector: EncodingDetector,
    private val store: PlaylistImportStore,
    private val fetchUrl: suspend (String) -> ByteArray,
    /** 保存上传的文件字节，返回可用于后续读取的本地路径（文件型源落盘） */
    private val saveFile: suspend (sourceId: String, bytes: ByteArray) -> String = { _, _ -> "" },
    /** 按保存的路径读取文件字节；文件不存在时应抛出异常（refresh 文件型源用） */
    private val readFile: suspend (path: String) -> ByteArray = { throw PlaylistImportException("该环境不支持读取文件源") },
) {

    /** 解析预览（URL 源，不落库）；若同 URL 源已存在，附带增量预测供冲突提示。 */
    suspend fun previewUrl(url: String): ImportPreview {
        val bytes = fetchUrl(url)
        val existingId = store.sourceByUrl(normalizeUrl(url))?.id
        return buildPreview(bytes, sourceName = sourceNameFromUrl(url), sourceUrl = url, existingSourceId = existingId)
    }

    /** 解析预览（上传文件，不落库）；sourceName 非空且匹配到已有文件源时附带增量预测。 */
    suspend fun previewBytes(fileName: String?, bytes: ByteArray, sourceName: String? = null): ImportPreview {
        val name = sourceName?.takeIf { it.isNotBlank() } ?: fileName ?: "未命名源"
        val existingId = if (sourceName.isNullOrBlank()) null else store.sourceByNameAndType(name, "file")?.id
        return buildPreview(bytes, sourceName = name, sourceUrl = null, existingSourceId = existingId)
    }

    /** 确认导入（URL 源）：重复导入同一 URL 复用其直播源做增量合并。 */
    suspend fun importUrl(url: String): ImportResult {
        val bytes = fetchUrl(url)
        val now = System.currentTimeMillis()
        return importContent(resolveUrlSource(url, now), bytes)
    }

    /**
     * 确认导入（上传文件）。
     *
     * @param sourceName 可选的源名；仅当其非空时按 (type=file, name) 匹配已有文件源，
     *   匹配到则复用其 id 做增量合并（等同 refresh），否则新建文件源；
     *   为空则保持 03 语义：每次上传视为新源
     */
    suspend fun importBytes(fileName: String?, bytes: ByteArray, sourceName: String? = null): ImportResult {
        val now = System.currentTimeMillis()
        val name = sourceName?.takeIf { it.isNotBlank() } ?: fileName ?: "未命名源"
        val source = if (sourceName.isNullOrBlank()) {
            newFileSource(name, now)
        } else {
            resolveFileSource(name, now)
        }
        // 文件内容落盘（无论新建或复用都刷新快照），url 存本地路径供 refresh 读回
        val path = saveFile(source.id, bytes)
        return importContent(source.copy(url = path), bytes)
    }

    /** 按 id 重新拉取直播源内容并增量合并（refresh）：URL 源重新拉 URL，文件源读回落盘内容。 */
    suspend fun refreshSource(sourceId: String): ImportResult {
        val source = store.sourceById(sourceId)
            ?: throw PlaylistImportException("直播源不存在")
        val bytes = when (source.type) {
            "file" -> {
                if (source.url.isBlank()) throw PlaylistImportException("文件源缺少文件引用")
                try {
                    readFile(source.url)
                } catch (e: PlaylistImportException) {
                    throw e
                } catch (e: Exception) {
                    throw PlaylistImportException("源文件不存在或不可读")
                }
            }
            else -> fetchUrl(source.url)
        }
        return importContent(source, bytes)
    }

    // ---- 内部 ----

    private suspend fun buildPreview(
        bytes: ByteArray,
        sourceName: String,
        sourceUrl: String?,
        existingSourceId: String? = null,
    ): ImportPreview {
        val decoded = encodingDetector.decodeDetected(bytes)
        val parsed = parser.parse(decoded.text)
        val prediction = existingSourceId?.let { id ->
            val existing = store.channelsBySource(id)
            val merge = mergeChannels(existing, parsed.channels, id, now = 0L)
            IncrementalPrediction(
                imported = merge.imported,
                updated = merge.updated,
                hidden = merge.hidden,
                existingCount = existing.size,
            )
        }
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
            imported = prediction?.imported,
            updated = prediction?.updated,
            hidden = prediction?.hidden,
            existingChannelCount = prediction?.existingCount,
        )
    }

    /** 解析 + 增量合并落库（ADR-0004），返回增/改/隐计数。 */
    private suspend fun importContent(source: PlaylistSourceEntity, bytes: ByteArray): ImportResult {
        val decoded = encodingDetector.decodeDetected(bytes)
        val parsed = parser.parse(decoded.text)
        val now = System.currentTimeMillis()
        val existing = store.channelsBySource(source.id)
        val merge = mergeChannels(existing, parsed.channels, source.id, now)
        store.applyImport(source.copy(lastImportedAt = now), merge.inserts, merge.updates, merge.hides)
        // 分组同步：每次导入（新增/更新/隐藏）都把解析出的分组名落 groups 表，
        // 电视端分组标签与 WebUI 分组管理页都读 groups 表，落库即生效。
        store.upsertGroups(parsed.groups)

        return ImportResult(
            imported = merge.imported,
            updated = merge.updated,
            hidden = merge.hidden,
            sourceId = source.id,
        )
    }

    /** URL 源：按归一化 URL 找已有源复用（增量合并），否则新建。 */
    private suspend fun resolveUrlSource(sourceUrl: String, now: Long): PlaylistSourceEntity {
        val normalizedUrl = normalizeUrl(sourceUrl)
        val existing = store.sourceByUrl(normalizedUrl)
        return if (existing != null) {
            // 复用已有直播源：保留用户重命名，仅刷新 lastImportedAt
            existing.copy(lastImportedAt = now)
        } else {
            PlaylistSourceEntity(
                id = UUID.randomUUID().toString(),
                name = sourceNameFromUrl(sourceUrl).ifBlank { "未命名源" },
                type = "url",
                url = normalizedUrl,
                lastImportedAt = now,
                createdAt = now,
            )
        }
    }

    /** 文件源：按 (type=file, name) 找已有源复用（增量合并），否则新建。 */
    private suspend fun resolveFileSource(name: String, now: Long): PlaylistSourceEntity {
        val existing = store.sourceByNameAndType(name, "file")
        return if (existing != null) {
            existing.copy(lastImportedAt = now)
        } else {
            newFileSource(name, now)
        }
    }

    private fun newFileSource(name: String, now: Long) = PlaylistSourceEntity(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "未命名源" },
        type = "file",
        url = "",
        lastImportedAt = now,
        createdAt = now,
    )
}
