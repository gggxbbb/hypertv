package icu.gxb.hypertv.ui

import icu.gxb.hypertv.player.Channel

/**
 * 频道列表浮层的分组过滤纯逻辑（ticket 05）。
 *
 * 标签序列 = 固定"全部" + 各分组（orderIndex 升序）+ 固定"收藏"；
 * "全部"与"收藏"是固定虚拟标签，不落库（spec Implementation Decisions）。
 * 纯函数，可 JVM 单测。
 */
object ChannelListFilter {

    const val TAB_ALL = "全部"
    const val TAB_FAVORITES = "收藏"

    /** 标签序列：全部 + 各分组（已按 orderIndex 升序）+ 收藏 */
    fun tabs(groups: List<String>): List<String> = listOf(TAB_ALL) + groups + listOf(TAB_FAVORITES)

    /** 按当前标签过滤频道；未知标签按空列表处理（防御） */
    fun filter(channels: List<Channel>, tab: String): List<Channel> = when (tab) {
        TAB_ALL -> channels
        TAB_FAVORITES -> channels.filter { it.isFavorite }
        else -> channels.filter { it.groupName == tab }
    }
}
