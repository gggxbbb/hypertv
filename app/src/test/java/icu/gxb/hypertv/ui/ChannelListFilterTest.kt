package icu.gxb.hypertv.ui

import icu.gxb.hypertv.player.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 频道列表浮层的分组过滤纯逻辑单测（ticket 05）。
 * 覆盖：标签序列构造、"全部/收藏/分组"过滤正确性、未知标签防御。
 */
class ChannelListFilterTest {

    private fun channel(id: String, group: String = "g1", favorite: Boolean = false) = Channel(
        id = id,
        name = "频道$id",
        url = "http://example.com/$id.m3u8",
        groupName = group,
        isFavorite = favorite,
        orderIndex = id.toInt() - 1,
    )

    @Test
    fun `tabs are all then groups then favorites`() {
        assertEquals(
            listOf(ChannelListFilter.TAB_ALL, "新闻", "体育", ChannelListFilter.TAB_FAVORITES),
            ChannelListFilter.tabs(listOf("新闻", "体育")),
        )
    }

    @Test
    fun `tabs with no groups are all and favorites only`() {
        assertEquals(
            listOf(ChannelListFilter.TAB_ALL, ChannelListFilter.TAB_FAVORITES),
            ChannelListFilter.tabs(emptyList()),
        )
    }

    @Test
    fun `all tab returns every channel in original order`() {
        val list = listOf(channel("1", "a"), channel("2", "b"), channel("3", "a"))
        assertEquals(list, ChannelListFilter.filter(list, ChannelListFilter.TAB_ALL))
    }

    @Test
    fun `favorites tab only returns favorite channels in order`() {
        val list = listOf(
            channel("1", favorite = false),
            channel("2", favorite = true),
            channel("3", favorite = true),
        )
        assertEquals(
            listOf("2", "3"),
            ChannelListFilter.filter(list, ChannelListFilter.TAB_FAVORITES).map { it.id },
        )
    }

    @Test
    fun `group tab only returns channels in that group`() {
        val list = listOf(channel("1", "a"), channel("2", "b"), channel("3", "a"))
        assertEquals(
            listOf("1", "3"),
            ChannelListFilter.filter(list, "a").map { it.id },
        )
    }

    @Test
    fun `group with no channels returns empty list`() {
        val list = listOf(channel("1", "a"))
        assertEquals(emptyList<Channel>(), ChannelListFilter.filter(list, "b"))
    }

    @Test
    fun `unknown tab returns empty list`() {
        assertEquals(
            emptyList<Channel>(),
            ChannelListFilter.filter(listOf(channel("1")), "不存在的标签"),
        )
    }
}
