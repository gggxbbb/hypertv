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

    // ---- 第五级：归一化包含匹配（v5）----

    @Test
    fun `level5 contains match hits when display name is embedded`() {
        // CCTV-风云剧场 → EPG 风云剧场（本地名有品牌前缀，归一化后子串命中）
        val xmltv = listOf(xmltvChannel("fengyun", "风云剧场"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-风云剧场", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("fengyun", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `level5 contains match hits when display name is a middle infix`() {
        // xxx风云剧场yyy → 风云剧场（中缀，前缀/后缀都有无关字符）
        val xmltv = listOf(xmltvChannel("fengyun", "风云剧场"))
        val channels = listOf(channel(id = "ch-1", name = "xxx风云剧场yyy", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("fengyun", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `prefix match takes precedence over contains match`() {
        // 已知取舍：前缀命中优先于包含命中。EPG 同时有 CCTV 与 风云剧场时，
        // CCTV-风云剧场 走 level4 命中 CCTV（若想绑风云剧场需用匹配规则）。
        val xmltv = listOf(
            xmltvChannel("cctv", "CCTV"),
            xmltvChannel("fengyun", "风云剧场"),
        )
        val channels = listOf(channel(id = "ch-1", name = "CCTV-风云剧场", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("cctv", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_PREFIX, result.levels["ch-1"])
    }

    @Test
    fun `level5 rejects short generic display names below threshold`() {
        // CCTV-1 综合：EPG「综合」归一化长度 2 < 4，包含匹配不参与 → 不命中
        val xmltv = listOf(xmltvChannel("zonghe", "综合"))
        val channels = listOf(channel(id = "ch-1", name = "CCTV-1 综合", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertFalse(result.mapping.containsKey("ch-1"))
        assertEquals(0, result.stats.matched)
        assertEquals(0, result.stats.level5)
    }

    @Test
    fun `level5 longest contains candidate wins`() {
        // 北京影视风云剧场频道：影视风云剧场(6) 与 风云剧场(4) 都包含 → 取更长者
        val xmltv = listOf(
            xmltvChannel("short", "风云剧场"),
            xmltvChannel("long", "影视风云剧场"),
        )
        val channels = listOf(channel(id = "ch-1", name = "北京影视风云剧场频道", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("long", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `level5 equal length contains candidates take first in source order`() {
        // 我的风云剧场人生 同时包含 风云剧场(4) 与 剧场人生(4)：均非前缀、同长，
        // 取源顺序先出现者（候选排序稳定，length 相同时保持 XMLTV 顺序）
        val xmltv = listOf(
            xmltvChannel("sheng", "剧场人生"),
            xmltvChannel("fengyun", "风云剧场"),
        )
        val channels = listOf(channel(id = "ch-1", name = "我的风云剧场人生", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("sheng", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `level5 english display name matches by contains`() {
        // StarSports 1 → starsports：前缀边界检查拒绝（后邻数字 1），包含匹配兜底命中
        val xmltv = listOf(xmltvChannel("ss", "starsports"))
        val channels = listOf(channel(id = "ch-1", name = "StarSports 1", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("ss", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `level5 does not let prefix collision sneak back in via contains`() {
        // 第四级边界拒绝的防误配在第五级必须同样守住：
        // - cctv1 在 cctv10 内子串命中（数字续数）→ 拒绝
        // - cctv1 在 cctv1x 内子串命中（非白名单字母后缀）→ 拒绝
        // - cctv1 在 cctv11 内子串命中（同型数字）→ 拒绝
        val xmltv = listOf(xmltvChannel("cctv1", "CCTV1"))
        val channels = listOf(
            channel(id = "a", name = "CCTV-10 科教", epgId = null),
            channel(id = "b", name = "CCTV-1X", epgId = null),
            channel(id = "c", name = "CCTV-11", epgId = null),
        )

        val result = matcher.match(xmltv, channels)

        assertFalse(result.mapping.containsKey("a"))
        assertFalse(result.mapping.containsKey("b"))
        assertFalse(result.mapping.containsKey("c"))
        assertEquals(0, result.stats.matched)
        assertEquals(0, result.stats.level5)
    }

    @Test
    fun `level5 accepts digit suffix for letter-ending ascii candidate`() {
        // 候选以字母结尾 + 频道名后邻纯数字 = 频道编号后缀（如 starsports + 1），接受
        val xmltv = listOf(xmltvChannel("sc", "sportscity"))
        val channels = listOf(channel(id = "ch-1", name = "Sportscity 2", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertEquals("sc", result.mapping["ch-1"])
        assertEquals(EpgMatchLevel.NORMALIZED_CONTAINS, result.levels["ch-1"])
    }

    @Test
    fun `level5 only fires after previous four levels fail`() {
        // 前四级可命中的场景 level5 不参与：
        // 1) 归一化精确（level3）优先于包含
        val exactXmltv = listOf(xmltvChannel("exact", "CCTV-风云剧场"))
        val exactResult = matcher.match(exactXmltv, listOf(channel(id = "a", name = "CCTV-风云剧场", epgId = null)))
        assertEquals(EpgMatchLevel.NORMALIZED_NAME, exactResult.levels["a"])

        // 2) 前缀（level4）优先于包含：CCTV-2 财经 命中 CCTV2 而非「财经」
        val prefixXmltv = listOf(
            xmltvChannel("cctv2", "CCTV2"),
            xmltvChannel("caijing", "财经"),
        )
        val prefixResult = matcher.match(prefixXmltv, listOf(channel(id = "b", name = "CCTV-2 财经", epgId = null)))
        assertEquals("cctv2", prefixResult.mapping["b"])
        assertEquals(EpgMatchLevel.NORMALIZED_PREFIX, prefixResult.levels["b"])
        assertEquals(0, prefixResult.stats.level5)
    }

    @Test
    fun `level5 stats are counted separately`() {
        val xmltv = listOf(xmltvChannel("fengyun", "风云剧场"))
        val channels = listOf(
            channel(id = "a", name = "CCTV-风云剧场", epgId = null),
            channel(id = "b", name = "完全无关", epgId = null),
        )

        val result = matcher.match(xmltv, channels)

        assertEquals(1, result.stats.level5)
        assertEquals(1, result.stats.matched)
        assertEquals(1, result.stats.unmatched)
    }

    @Test
    fun `level5 does not match when display name longer than channel name`() {
        val xmltv = listOf(xmltvChannel("long", "风云剧场精品台"))
        val channels = listOf(channel(id = "ch-1", name = "风云剧场", epgId = null))

        val result = matcher.match(xmltv, channels)

        assertFalse(result.mapping.containsKey("ch-1"))
    }
}
