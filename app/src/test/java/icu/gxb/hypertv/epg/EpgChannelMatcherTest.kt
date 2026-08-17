package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgChannelMatcherTest {

    private val matcher = EpgChannelMatcher()

    private fun xmltvChannel(id: String, vararg names: String) = EpgChannel(id = id, displayNames = names.toList(), iconUrl = null)

    private fun channel(
        id: String,
        name: String,
        epgId: String? = null,
        groupName: String = "新闻",
    ) = ChannelEntity(
        id = id,
        sourceId = "src-1",
        name = name,
        url = "http://stream.example.com/$id.m3u8",
        groupName = groupName,
        logoUrl = null,
        orderIndex = 0,
        epgId = epgId,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 100L,
    )

    // ---- 三级优先级 ----

    @Test
    fun `exact tvg-id match has highest priority`() {
        // 频道 epgId 精确命中，尽管 display-name 与频道名不同
        val xmltv = listOf(
            xmltvChannel("cctv1.example", "CCTV 1 频道"),
            xmltvChannel("other.example", "CCTV-1"),
        )
        val channels = listOf(channel(id = "ch-1", name = "CCTV-1", epgId = "cctv1.example"))

        val result = matcher.match(xmltv, channels)

        assertEquals("cctv1.example", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.EXACT_TVG_ID, result.levels["ch-1"])
    }

    @Test
    fun `case-insensitive tvg-id match is level two`() {
        val xmltv = listOf(xmltvChannel("CCTV-1", "CCTV-1"))
        val channels = listOf(channel(id = "ch-1", name = "一号台", epgId = "cctv-1"))

        val result = matcher.match(xmltv, channels)

        assertEquals("CCTV-1", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.CASE_INSENSITIVE_TVG_ID, result.levels["ch-1"])
    }

    @Test
    fun `normalized name match is level three`() {
        // 无 epgId：靠频道名匹配 XMLTV display-name
        val xmltv = listOf(xmltvChannel("cctv5.example", "CCTV-5 体育"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-5 体育", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("cctv5.example", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_NAME, result.levels["ch-1"])
    }

    @Test
    fun `exact id wins over name even when name also matches`() {
        val xmltv = listOf(
            xmltvChannel("alpha", "CCTV-1"),
            xmltvChannel("beta", "CCTV-1"),
        )
        // epgId=alpha：精确命中；同时名字归一化后与两个候选都相同（取首个 beta）
        val channels = listOf(channel(id = "ch-1", name = "CCTV-1", epgId = "alpha"))

        val result = matcher.match(xmltv, channels)

        assertEquals("alpha", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.EXACT_TVG_ID, result.levels["ch-1"])
    }

    @Test
    fun `unmatched channels are left empty`() {
        val xmltv = listOf(xmltvChannel("c1", "甲"))
        val channels = listOf(
            channel(id = "a", name = "甲"),
            channel(id = "b", name = "乙", epgId = "nope"),
            channel(id = "c", name = "完全不相关"),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals(1, result.mapping.size)
        assertTrue(result.mapping.containsKey("a"))
        assertFalse(result.mapping.containsKey("b"))
        assertFalse(result.mapping.containsKey("c"))
        assertEquals(1, result.stats.matched)
        assertEquals(2, result.stats.unmatched)
    }

    // ---- 归一化边界 ----

    @Test
    fun `normalization handles full width spaces and punctuation`() {
        assertEquals("cctv1综合", EpgChannelMatcher.normalizeName("ＣＣＴＶ-１　综合"))
        assertEquals("cctv1综合", EpgChannelMatcher.normalizeName("CCTV-1 综合"))
        assertEquals("央视新闻", EpgChannelMatcher.normalizeName("央视·新闻"))
        assertEquals("cctv5", EpgChannelMatcher.normalizeName("CCTV5"))
        assertEquals("cctv5", EpgChannelMatcher.normalizeName("cctv5"))
        assertEquals("", EpgChannelMatcher.normalizeName("  ---  ，。  "))
    }

    @Test
    fun `name match is insensitive to whitespace and punctuation`() {
        val xmltv = listOf(xmltvChannel("ch_news", "新闻·综合"))
        val channels = listOf(channel(id = "a", name = "新闻 综合", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("ch_news", result.mapping["a"])
        assertEquals(EpgMatchLevel.NORMALIZED_NAME, result.levels["a"])
    }

    @Test
    fun `first xmltv candidate wins for equal normalized names`() {
        val xmltv = listOf(
            xmltvChannel("first", "CCTV-1"),
            xmltvChannel("second", "CCTV 1"),
        )
        val channels = listOf(channel(id = "a", name = "CCTV-1", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("first", result.mapping["a"])
    }

    // ---- 统计 ----

    @Test
    fun `stats count levels and rate`() {
        val xmltv = listOf(
            xmltvChannel("exact", "精确"),
            xmltvChannel("CI", "忽略大小写"),
            xmltvChannel("name", "名字匹配"),
            xmltvChannel("unused", "没频道用"),
        )
        val channels = listOf(
            channel(id = "a", name = "A", epgId = "exact"),
            channel(id = "b", name = "B", epgId = "ci"), // 大小写不同
            channel(id = "c", name = "名字匹配", epgId = null),
            channel(id = "d", name = "无匹配"),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals(4, result.stats.total)
        assertEquals(3, result.stats.matched)
        assertEquals(1, result.stats.level1)
        assertEquals(1, result.stats.level2)
        assertEquals(1, result.stats.level3)
        assertEquals(0.75, result.stats.rate, 0.001)
    }
}
