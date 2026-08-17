package icu.gxb.hypertv.server

import kotlinx.serialization.Serializable

/** POST /api/playlist/import 与 /import/preview 的请求体。 */
@Serializable
data class ImportUrlRequest(val url: String)

/** 导入预览响应的单条频道摘要（只取 WebUI 展示需要的字段）。 */
@Serializable
data class ChannelPreview(
    val name: String,
    val url: String,
    val groupName: String,
    val logoUrl: String?,
    val epgId: String?,
)

/**
 * 解析预览响应（不落库）：频道总数、分组列表、前 [PREVIEW_LIMIT] 条频道、
 * 识别出的编码、默认源名与源 URL。
 *
 * imported/updated/hidden/existingChannelCount 为可选增量预测（ticket 08）：
 * 仅当预览内容能匹配到已有直播源（URL 导入同 URL / 文件导入同 sourceName）时非 null，
 * 供 WebUI 展示"将新增/更新/隐藏"的冲突提示；无匹配源时保持 null（全新导入）。
 */
@Serializable
data class ImportPreview(
    val total: Int,
    val groups: List<String>,
    val preview: List<ChannelPreview>,
    val encoding: String,
    val sourceName: String,
    val url: String?,
    val imported: Int? = null,
    val updated: Int? = null,
    val hidden: Int? = null,
    val existingChannelCount: Int? = null,
)

/** 导入执行响应：新增/更新/隐藏计数与所属直播源 id。 */
@Serializable
data class ImportResult(
    val imported: Int,
    val updated: Int,
    val hidden: Int,
    val sourceId: String,
)

/** 导入/上传失败时的错误响应。 */
@Serializable
data class ImportError(val error: String)
