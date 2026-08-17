package icu.gxb.hypertv.m3u

import icu.gxb.hypertv.data.entity.ChannelEntity
import java.util.UUID

/**
 * 频道 URL 归一化：去首尾空白 + 全小写，用于增量合并的匹配键（ADR-0004）。
 * 仅用于匹配，不修改入库 URL（入库 URL 使用去除首尾空白后的原值）。
 */
fun normalizeUrl(url: String): String = url.trim().lowercase()

/**
 * 增量合并结果：三类待写入频道 + 计数。
 *
 * - inserts：新增频道（追加到队尾，orderIndex 续排）
 * - updates：URL 匹配到的既有频道（保留 id/sourceId/orderIndex/isFavorite/createdAt，
 *   元数据用新值覆盖，isHidden 复位）
 * - hides：源内已消失的既有频道（仅置 isHidden=true，不删除）
 */
data class MergeResult(
    val inserts: List<ChannelEntity>,
    val updates: List<ChannelEntity>,
    val hides: List<ChannelEntity>,
) {
    val imported: Int get() = inserts.size
    val updated: Int get() = updates.size
    val hidden: Int get() = hides.size
}

/**
 * 按频道 URL 增量合并（ADR-0004）：重复导入同一直播源时，
 * 已存在频道保留收藏、自定义顺序与 ID，仅更新名称/台标/分组/EPG 字段；
 * 新增频道追加到队尾；源内消失频道标记隐藏而非删除。
 *
 * 同一源内 URL 重复的传入频道只保留第一条。
 *
 * @param existing 该 sourceId 下现有频道（需同属一个直播源）
 * @param incoming 本次解析出的频道列表
 * @param sourceId 目标直播源 id
 * @param now 当前时间戳（写入 createdAt，便于测试注入）
 */
fun mergeChannels(
    existing: List<ChannelEntity>,
    incoming: List<NewChannel>,
    sourceId: String,
    now: Long = System.currentTimeMillis(),
): MergeResult {
    val byUrl = existing.associateBy { normalizeUrl(it.url) }
    val incomingDistinct = incoming.distinctBy { normalizeUrl(it.url) }

    val matched = HashSet<String>(incomingDistinct.size)
    val updates = ArrayList<ChannelEntity>(incomingDistinct.size)
    val inserts = ArrayList<ChannelEntity>()
    var nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1

    for (ch in incomingDistinct) {
        val key = normalizeUrl(ch.url)
        val old = byUrl[key]
        if (old != null) {
            matched.add(key)
            updates += old.copy(
                name = ch.name,
                url = ch.url.trim(),
                groupName = ch.groupName,
                logoUrl = ch.logoUrl,
                epgId = ch.epgId,
                catchup = ch.catchup,
                catchupDays = ch.catchupDays,
                catchupSource = ch.catchupSource,
                isHidden = false,
            )
        } else {
            inserts += ChannelEntity(
                id = UUID.randomUUID().toString(),
                sourceId = sourceId,
                name = ch.name,
                url = ch.url.trim(),
                groupName = ch.groupName,
                logoUrl = ch.logoUrl,
                orderIndex = nextOrder++,
                isFavorite = false,
                isHidden = false,
                epgId = ch.epgId,
                catchup = ch.catchup,
                catchupDays = ch.catchupDays,
                catchupSource = ch.catchupSource,
                createdAt = now,
            )
        }
    }

    val hides = existing
        .asSequence()
        .filter { normalizeUrl(it.url) !in matched }
        .map { it.copy(isHidden = true) }
        .toList()

    return MergeResult(inserts = inserts, updates = updates, hides = hides)
}
