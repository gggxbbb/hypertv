package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 匹配规则纯函数测试（v3 手动匹配）。 */
class MatchRuleApplierTest {

    private fun channel(
        id: String,
        name: String,
        epgId: String? = null,
        epgManual: Boolean = false,
    ) = ChannelEntity(
        id = id,
        sourceId = "src-1",
        name = name,
        url = "http://stream.example.com/$id.m3u8",
        groupName = "新闻",
        logoUrl = null,
        orderIndex = 0,
        epgId = epgId,
        epgManual = epgManual,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 100L,
    )

    private fun rule(epgChannelId: String, keyword: String, type: String) =
        MatchRule(epgChannelId = epgChannelId, keyword = keyword, ruleType = type)

    @Test
    fun `prefix rule matches channel names starting with keyword`() {
        val channels = listOf(channel("a", "CCTV-1 综合"), channel("b", "明珠台"))
        val rules = listOf(rule("cctv1.example", "CCTV-1", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        assertEquals(listOf("a" to "cctv1.example"), result.updates)
        assertEquals(listOf(1), result.hits)
        assertEquals(1, result.appliedCount)
    }

    @Test
    fun `contains rule matches channel names containing keyword`() {
        val channels = listOf(
            channel("a", "央视CCTV-5体育频道"),
            channel("b", "湖南卫视"),
        )
        val rules = listOf(rule("cctv5.example", "CCTV-5", MatchRuleType.CONTAINS))

        val result = applyMatchRules(channels, rules)

        assertEquals(listOf("a" to "cctv5.example"), result.updates)
    }

    @Test
    fun `one rule hits multiple channels with different resolutions`() {
        val channels = listOf(
            channel("a", "CCTV-1"),
            channel("b", "CCTV-1HD"),
            channel("c", "CCTV-1标清"),
            channel("d", "CCTV-2"),
        )
        val rules = listOf(rule("cctv1.example", "CCTV-1", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        // 关键字 CCTV-1 命中同一 EPG 频道的多个清晰度源
        assertEquals(3, result.appliedCount)
        assertEquals(setOf("a", "b", "c"), result.updates.map { it.first }.toSet())
        assertTrue(result.updates.all { it.second == "cctv1.example" })
        assertEquals(listOf(3), result.hits)
    }

    @Test
    fun `matching is case insensitive`() {
        val channels = listOf(channel("a", "cctv-1 综合"), channel("b", "CCTV1"))
        val rules = listOf(rule("cctv1.example", "cctv-1", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        assertEquals(listOf("a" to "cctv1.example"), result.updates)
    }

    @Test
    fun `does not touch channels with existing epgId`() {
        // 已有 epgId = 三级自动匹配过 → 规则不覆盖
        val channels = listOf(channel("a", "CCTV-1 综合", epgId = "existing.example"))
        val rules = listOf(rule("cctv1.example", "CCTV-1", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun `does not touch manually bound channels`() {
        val channels = listOf(channel("a", "CCTV-1 综合", epgId = "manual.example", epgManual = true))
        val rules = listOf(rule("cctv1.example", "CCTV-1", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun `first matching rule wins per channel`() {
        val channels = listOf(channel("a", "CCTV-1 综合"), channel("b", "CCTV-5 体育"))
        val rules = listOf(
            rule("cctv1.example", "CCTV-1", MatchRuleType.PREFIX),
            rule("cctv_all.example", "CCTV", MatchRuleType.PREFIX),
        )

        val result = applyMatchRules(channels, rules)

        // a 命中第一条；b 被第一条放行后命中第二条
        assertEquals("cctv1.example", result.updates.first { it.first == "a" }.second)
        assertEquals("cctv_all.example", result.updates.first { it.first == "b" }.second)
        assertEquals(listOf(1, 1), result.hits)
    }

    @Test
    fun `empty rules produce no updates`() {
        val channels = listOf(channel("a", "CCTV-1 综合"))

        val result = applyMatchRules(channels, emptyList())

        assertTrue(result.updates.isEmpty())
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `blank keyword never matches`() {
        val channels = listOf(channel("a", "CCTV-1 综合"))
        val rules = listOf(rule("cctv1.example", "  ", MatchRuleType.PREFIX))

        val result = applyMatchRules(channels, rules)

        assertTrue(result.updates.isEmpty())
    }
}
