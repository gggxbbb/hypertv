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
        assertEquals(0, result.stats.level4)
        assertEquals(0.75, result.stats.rate, 0.001)
    }

    // ---- 第四级：归一化前缀匹配（v4）----

    @Test
    fun `level4 prefix match hits when suffix starts with chinese`() {
        // CCTV-2 财经 → EPG CCTV2：后邻「财」（汉字）→ 接受
        val xmltv = listOf(xmltvChannel("cctv2", "CCTV2"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-2 财经", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("cctv2", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_PREFIX, result.levels["ch-1"])
    }

    @Test
    fun `level4 rejects when prefix followed by digit`() {
        // CCTV-10 科教 → EPG CCTV1：后邻 '0'（数字且不在白名单）→ 拒绝
        val xmltv = listOf(xmltvChannel("cctv1", "CCTV1"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-10 科教", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertFalse(result.mapping.containsKey("ch-1"))
        assertEquals(0, result.stats.matched)
    }

    @Test
    fun `level4 rejects when prefix followed by non whitelist letter`() {
        // CCTV-1X 类推：后邻 'x' 字母且不在白名单 → 拒绝
        val xmltv = listOf(xmltvChannel("cctv1", "CCTV1"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-1X", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertFalse(result.mapping.containsKey("ch-1"))
    }

    @Test
    fun `level4 accepts whitelist resolution suffix`() {
        // 欢笑剧场4K → EPG 欢笑剧场：后邻 '4k'（白名单）→ 接受
        val xmltv = listOf(xmltvChannel("huaixiao", "欢笑剧场"))
        val channels = listOf(channel(id = "ch-1", name = "欢笑剧场4K", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("huaixiao", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_PREFIX, result.levels["ch-1"])
    }

    @Test
    fun `level4 accepts hd uhd and cjk resolution combos`() {
        val xmltv = listOf(
            xmltvChannel("hd", "CCTV1"),
            xmltvChannel("uhd", "CCTV5"),
            xmltvChannel("4k-uhd", "CCTV6"),
        )
        val channels = listOf(
            channel(id = "a", name = "CCTV-1 HD", epgId = null),
            channel(id = "b", name = "CCTV-5 UHD", epgId = null),
            channel(id = "c", name = "CCTV-6 4K超高清", epgId = null),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals("hd", result.mapping["a"])
        assertEquals("uhd", result.mapping["b"])
        assertEquals("4k-uhd", result.mapping["c"])
    }

    @Test
    fun `level4 longest prefix wins`() {
        // 欢笑剧场4K：候选 欢笑剧场（长）与 欢笑（短）都前缀命中 → 取更长者
        val xmltv = listOf(
            xmltvChannel("short", "欢笑"),
            xmltvChannel("long", "欢笑剧场"),
        )
        val channels = listOf(channel(id = "ch-1", name = "欢笑剧场4K", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("long", result.mapping["ch-1"])
    }

    @Test
    fun `level4 preferred longer cctv4k over cctv4 for 4k channel`() {
        val xmltv = listOf(
            xmltvChannel("4", "CCTV4"),
            xmltvChannel("4k", "CCTV4K"),
        )
        val channels = listOf(channel(id = "ch-1", name = "CCTV-4K 超高清", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("4k", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_PREFIX, result.levels["ch-1"])
    }

    @Test
    fun `level4 does not override existing three levels`() {
        // ch-1 有 epgId 精确命中（即使前缀也命中）
        val xmltv = listOf(
            xmltvChannel("exact-id", "CCTV-2 财经"),
            xmltvChannel("2", "CCTV2"),
        )
        val channels = listOf(
            channel(id = "ch-1", name = "CCTV-2 财经", epgId = "exact-id"),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals("exact-id", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.EXACT_TVG_ID, result.levels["ch-1"])
    }

    @Test
    fun `level4 only fires after exact normalized name fails`() {
        // 频道名与某 display-name 完全相等 → 仍走三级精确，不降级为前缀
        val xmltv = listOf(xmltvChannel("exact", "CCTV-2 财经"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-2 财经", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("exact", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_NAME, result.levels["ch-1"])
    }

    @Test
    fun `level4 stats are counted separately`() {
        val xmltv = listOf(xmltvChannel("cctv2", "CCTV2"))
        val channels = listOf(
            channel(id = "a", name = "CCTV-2 财经", epgId = null),
            channel(id = "b", name = "完全无关", epgId = null),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals(1, result.stats.level4)
        assertEquals(1, result.stats.matched)
        assertEquals(1, result.stats.unmatched)
    }
}
