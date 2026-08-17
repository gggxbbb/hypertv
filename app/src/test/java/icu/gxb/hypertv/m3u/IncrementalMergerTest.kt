package icu.gxb.hypertv.m3u

import icu.gxb.hypertv.data.entity.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalMergerTest {

    private fun channel(
        id: String,
        name: String = "频道 $id",
        url: String,
        orderIndex: Int = 0,
        isFavorite: Boolean = false,
        isHidden: Boolean = false,
        groupName: String = "默认",
    ) = ChannelEntity(
        id = id,
        sourceId = "src-1",
        name = name,
        url = url,
        groupName = groupName,
        logoUrl = null,
        orderIndex = orderIndex,
        isFavorite = isFavorite,
        isHidden = isHidden,
        epgId = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 100L,
    )

    private fun incoming(
        url: String,
        name: String = "新频道",
        groupName: String = "默认",
        logoUrl: String? = null,
        epgId: String? = null,
    ) = NewChannel(
        name = name,
        url = url,
        groupName = groupName,
        logoUrl = logoUrl,
        epgId = epgId,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
    )

    // ---- 基础 ----

    @Test
    fun `all new channels are appended with continuing orderIndex`() {
        val existing = listOf(channel("a", url = "http://s/a.m3u8", orderIndex = 0))
        val result = mergeChannels(
            existing,
            listOf(
                incoming("http://s/a.m3u8", "A"),
                incoming("http://s/b.m3u8", "B"),
                incoming("http://s/c.m3u8", "C"),
            ),
            "src-1",
        )

        assertEquals(2, result.imported)
        assertEquals(1, result.updated)
        assertEquals(0, result.hidden)
        assertEquals(1, result.inserts[0].orderIndex)
        assertEquals(2, result.inserts[1].orderIndex)
        assertTrue(result.inserts.all { it.sourceId == "src-1" && it.isFavorite.not() && !it.isHidden })
    }

    @Test
    fun `existing channels match by url and get metadata updated`() {
        val existing = listOf(
            channel("a", url = "http://s/a.m3u8", orderIndex = 3, isFavorite = true),
        )
        val result = mergeChannels(
            existing,
            listOf(incoming("http://s/a.m3u8", "A 高清", groupName = "新闻", epgId = "epg-a")),
            "src-1",
        )

        assertEquals(1, result.updated)
        assertEquals(0, result.imported)
        assertEquals(0, result.hidden)
        val updated = result.updates[0]
        assertEquals("a", updated.id)          // id 保留
        assertEquals(3, updated.orderIndex)    // orderIndex 保留
        assertTrue(updated.isFavorite)         // 收藏保留
        assertEquals("A 高清", updated.name)   // 元数据更新
        assertEquals("新闻", updated.groupName)
        assertEquals("epg-a", updated.epgId)
        assertFalse(updated.isHidden)
    }

    @Test
    fun `disappeared channels are hidden not deleted`() {
        val existing = listOf(
            channel("a", url = "http://s/a.m3u8", orderIndex = 0, isFavorite = true),
            channel("b", url = "http://s/b.m3u8", orderIndex = 1),
            channel("c", url = "http://s/c.m3u8", orderIndex = 2),
        )
        val result = mergeChannels(
            existing,
            listOf(incoming("http://s/a.m3u8", "A")),
            "src-1",
        )

        assertEquals(1, result.updated)
        assertEquals(0, result.imported)
        assertEquals(2, result.hidden)
        assertTrue(result.hides.map { it.id }.toSet() == setOf("b", "c"))
        assertTrue(result.hides.all { it.isHidden })
        // 隐藏的频道保留收藏与排序
        val hiddenB = result.hides.first { it.id == "b" }
        assertEquals(1, hiddenB.orderIndex)
        assertFalse(hiddenB.isFavorite)
    }

    @Test
    fun `reappearing channel is restored with favorite preserved`() {
        val existing = listOf(
            channel("a", url = "http://s/a.m3u8", orderIndex = 0, isFavorite = true, isHidden = true),
        )
        val result = mergeChannels(existing, listOf(incoming("http://s/a.m3u8", "A")), "src-1")

        assertEquals(1, result.updated)
        val updated = result.updates[0]
        assertFalse(updated.isHidden)
        assertTrue(updated.isFavorite)
        assertEquals(0, updated.orderIndex)
    }

    // ---- URL 归一化 ----

    @Test
    fun `url matching is case and whitespace insensitive`() {
        val existing = listOf(channel("a", url = "HTTP://Example.com/CCTV1.M3U8 "))
        val result = mergeChannels(
            existing,
            listOf(incoming("http://example.com/cctv1.m3u8", "CCTV1")),
            "src-1",
        )

        assertEquals(1, result.updated)
        assertEquals(0, result.imported)
        assertEquals("a", result.updates[0].id)
        // 入库 URL 为去掉首尾空白后的原值（不强制小写）
        assertEquals("http://example.com/cctv1.m3u8", result.updates[0].url)
    }

    @Test
    fun `trailing whitespace in stored url is handled`() {
        val existing = listOf(channel("a", url = "http://s/a.m3u8  "))
        val result = mergeChannels(existing, listOf(incoming("http://s/a.m3u8", "A")), "src-1")

        assertEquals(1, result.updated)
        assertEquals("http://s/a.m3u8", result.updates[0].url)
    }

    @Test
    fun `duplicate urls in incoming keep only first`() {
        val result = mergeChannels(
            emptyList(),
            listOf(
                incoming("http://s/a.m3u8", "A1", groupName = "甲"),
                incoming("http://S/A.M3U8", "A2", groupName = "乙"),
            ),
            "src-1",
        )

        assertEquals(1, result.imported)
        assertEquals("A1", result.inserts[0].name)
        assertEquals("甲", result.inserts[0].groupName)
    }

    @Test
    fun `new channels appended after hidden ones keep global ordering`() {
        val existing = listOf(
            channel("a", url = "http://s/a.m3u8", orderIndex = 0),
            channel("b", url = "http://s/b.m3u8", orderIndex = 1, isHidden = true),
        )
        val result = mergeChannels(existing, listOf(incoming("http://s/c.m3u8", "C")), "src-1")

        // a、b 在源中均消失被隐藏，但其 orderIndex 仍占用；c 排在全局队尾
        assertEquals(2, result.inserts[0].orderIndex)
        assertEquals(setOf("a", "b"), result.hides.map { it.id }.toSet())
    }

    @Test
    fun `empty incoming marks all existing hidden`() {
        val existing = listOf(channel("a", url = "http://s/a.m3u8", orderIndex = 0))
        val result = mergeChannels(existing, emptyList(), "src-1")

        assertEquals(0, result.updated)
        assertEquals(1, result.hidden)
    }
}
