package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpgRefresherTest {

    private val xmltvXml = """
        <tv>
          <channel id="cctv1.example"><display-name>CCTV-1 综合</display-name></channel>
          <channel id="cctv5.example"><display-name>CCTV-5 体育</display-name></channel>
          <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
            <title>新闻联播</title>
          </programme>
          <programme start="20260817003000 +0000" stop="20260817010000 +0000" channel="cctv5.example">
            <title>体育新闻</title>
          </programme>
        </tv>
    """.trimIndent()

    private val nowMillis = 1786924800000L // 2026-08-17T00:00:00Z

    private fun channel(
        id: String,
        name: String,
        epgId: String? = null,
        groupName: String = "新闻",
        epgManual: Boolean = false,
    ) = ChannelEntity(
        id = id,
        sourceId = "src-1",
        name = name,
        url = "http://stream.example.com/$id.m3u8",
        groupName = groupName,
        logoUrl = null,
        orderIndex = 0,
        epgId = epgId,
        epgManual = epgManual,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 100L,
    )

    private fun source(url: String, id: Long = 1, order: Int = 0, enabled: Boolean = true) =
        EpgSourceEntity(id = id, url = url, enabled = enabled, orderIndex = order)

    private fun refresher(
        store: FakeEpgStore,
        fetcher: suspend (String) -> ByteArray,
        now: () -> Long = { nowMillis },
    ) = EpgRefresher(store = store, fetcher = fetcher, now = now)

    @Test
    fun `global refresh parses matches writes and updates last update`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            // ch-2 无 epgId：靠频道名归一化匹配（三级），刷新后回写 xmltvId
            channels += channel("ch-2", "CCTV-5 体育", epgId = null)
            channels += channel("ch-3", "无法匹配")
        }
        var fetchedUrl: String? = null
        val refresher = refresher(store, fetcher = { url ->
            fetchedUrl = url
            xmltvXml.toByteArray()
        })

        val result = refresher.refreshGlobal()

        assertEquals("global", result.scope)
        assertEquals(3, result.stats.total)
        assertEquals(2, result.stats.matched)
        assertEquals(1, result.stats.level1)
        assertEquals(1, result.stats.level3)
        assertEquals(2, result.programsWritten)
        assertEquals("http://epg.example.com/xmltv.xml", fetchedUrl)

        // 写库：只有被匹配的 XMLTV 频道的节目被写入
        assertEquals(listOf("cctv1.example", "cctv5.example"), store.programs.map { it.channelEpgId }.sorted())
        // 频道 epgId 已回写：ch-1 本就相等不写，ch-2 从 null 补全，ch-3 未匹配不动
        assertEquals(listOf("ch-2" to "cctv5.example"), store.epgIdWrites)
        assertEquals("cctv5.example", store.channels.first { it.id == "ch-2" }.epgId)
        assertNull(store.channels.first { it.id == "ch-3" }.epgId)
        // last_update 更新 + 过期清理已执行
        assertEquals(nowMillis, store.lastUpdate)
        assertTrue(store.expiredCleanups.contains(nowMillis))
        // 状态为成功
        assertFalse(refresher.status.isRunning())
        assertNull(refresher.status.snapshot().lastError)
        assertEquals(nowMillis, refresher.status.snapshot().lastFinishedAt)
    }

    @Test
    fun `global refresh clears old programs by current epgIds before writing`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            // 预置一条旧节目（ch-1 之前匹配到 cctv1.example 的旧数据）
            programs += EpgProgramEntity(
                id = "cctv1.example|999",
                channelEpgId = "cctv1.example",
                title = "旧节目",
                description = null,
                startTime = 1000L,
                endTime = 2000L,
                category = null,
            )
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        refresher.refreshGlobal()

        // 旧 epgId（cctv1.example）被删除，随后只写入新数据（该频道当天 1 条）
        assertTrue(store.deletedEpgIds.contains(listOf("cctv1.example")))
        assertEquals(1, store.programs.size)
        assertTrue(store.programs.none { it.title == "旧节目" })
    }

    @Test
    fun `global refresh respects group overrides`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            groups += GroupEntity(name = "体育", orderIndex = 0, isCollapsed = false, epgUrl = "http://sports.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example", groupName = "新闻")
            channels += channel("ch-5", "CCTV-5 体育", epgId = "cctv5.example", groupName = "体育")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        val result = refresher.refreshGlobal()

        // 体育分组配置了独立源 → 全局刷新只匹配新闻分组的 ch-1
        assertEquals(1, result.stats.total)
        assertEquals(1, result.stats.matched)
        assertEquals(listOf("cctv1.example"), store.programs.map { it.channelEpgId })
        // ch-5 不受全局刷新影响（epgId 保持原样）
        assertEquals("cctv5.example", store.channels.first { it.id == "ch-5" }.epgId)
    }

    @Test
    fun `group refresh uses group source and matches only its channels`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://global.example.com/xmltv.xml")
            groups += GroupEntity(name = "体育", orderIndex = 0, isCollapsed = false, epgUrl = "http://sports.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example", groupName = "新闻")
            channels += channel("ch-5", "CCTV-5 体育", epgId = "cctv5.example", groupName = "体育")
        }
        var fetchedUrl: String? = null
        val refresher = refresher(store, fetcher = { url ->
            fetchedUrl = url
            xmltvXml.toByteArray()
        })

        val result = refresher.refreshGroup("体育")

        assertEquals("group:体育", result.scope)
        assertEquals("http://sports.example.com/xmltv.xml", fetchedUrl)
        assertEquals(1, result.stats.total)
        assertEquals(1, result.stats.matched)
        assertEquals(listOf("cctv5.example"), store.programs.map { it.channelEpgId })
    }

    @Test
    fun `group refresh falls back to global sources when group has no own source`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://global.example.com/xmltv.xml")
            groups += GroupEntity(name = "新闻", orderIndex = 0, isCollapsed = false)
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example", groupName = "新闻")
        }
        var fetchedUrl: String? = null
        val refresher = refresher(store, fetcher = { url ->
            fetchedUrl = url
            xmltvXml.toByteArray()
        })

        refresher.refreshGroup("新闻")

        assertEquals("http://global.example.com/xmltv.xml", fetchedUrl)
        assertEquals(1, store.programs.size)
    }

    @Test
    fun `fetch failure marks error and does not write`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { throw EpgException("拉取 EPG 源失败：连接超时") })

        try {
            refresher.refreshGlobal()
            fail("应抛出 EpgException")
        } catch (_: EpgException) {
        }

        assertFalse(refresher.status.isRunning())
        assertTrue(refresher.status.snapshot().lastError?.contains("拉取") == true)
        assertTrue(store.programs.isEmpty())
        assertNull(store.lastUpdate)
    }

    @Test
    fun `refresh without configured source throws`() = runTest {
        val store = FakeEpgStore() // 未配置全局源
        val refresher = refresher(store, fetcher = { error("不应拉取") })

        try {
            refresher.refreshGlobal()
            fail("应抛出 EpgException")
        } catch (e: EpgException) {
            assertTrue(e.message.orEmpty().contains("未配置"))
        }
    }

    @Test
    fun `refresh missing group throws`() = runTest {
        val store = FakeEpgStore()
        val refresher = refresher(store, fetcher = { error("不应拉取") })

        try {
            refresher.refreshGroup("不存在")
            fail("应抛出 EpgException")
        } catch (e: EpgException) {
            assertTrue(e.message.orEmpty().contains("不存在"))
        }
    }

    // ---- v3 多源合并 ----

    private val sourceAXml = """
        <tv>
          <channel id="cctv1.example"><display-name>CCTV-1 综合</display-name></channel>
          <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
            <title>A 源新闻</title>
          </programme>
        </tv>
    """.trimIndent()

    private val sourceBXml = """
        <tv>
          <channel id="cctv2.example"><display-name>CCTV-2 财经</display-name></channel>
          <programme start="20260817003000 +0000" stop="20260817010000 +0000" channel="cctv2.example">
            <title>B 源财经</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `multi source merge keeps programs from all sources`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://a.example/xmltv.xml", id = 1, order = 0)
            sources += source("http://b.example/xmltv.xml", id = 2, order = 1)
            channels += channel("ch-1", "CCTV-1 综合")
            channels += channel("ch-2", "CCTV-2 财经")
        }
        val refresher = refresher(store, fetcher = { url ->
            when (url) {
                "http://a.example/xmltv.xml" -> sourceAXml.toByteArray()
                "http://b.example/xmltv.xml" -> sourceBXml.toByteArray()
                else -> error("unexpected url: $url")
            }
        })

        val result = refresher.refreshGlobal()

        assertEquals(2, result.stats.matched)
        assertEquals(2, result.programsWritten)
        assertEquals(2, store.programs.size)
        assertEquals(
            setOf("cctv1.example", "cctv2.example"),
            store.programs.map { it.channelEpgId }.toSet(),
        )
        assertTrue(store.programs.any { it.title == "A 源新闻" })
        assertTrue(store.programs.any { it.title == "B 源财经" })
    }

    @Test
    fun `multi source merge later source wins on same channel and start time`() = runTest {
        val conflictSourceA = """
            <tv>
              <channel id="cctv1.example"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
                <title>旧源节目</title>
              </programme>
            </tv>
        """.trimIndent()
        val conflictSourceB = """
            <tv>
              <channel id="cctv1.example"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
                <title>新源节目</title>
              </programme>
            </tv>
        """.trimIndent()
        val store = FakeEpgStore().apply {
            sources += source("http://a.example/xmltv.xml", id = 1, order = 0)
            sources += source("http://b.example/xmltv.xml", id = 2, order = 1)
            channels += channel("ch-1", "CCTV-1 综合")
        }
        val refresher = refresher(store, fetcher = { url ->
            when (url) {
                "http://a.example/xmltv.xml" -> conflictSourceA.toByteArray()
                "http://b.example/xmltv.xml" -> conflictSourceB.toByteArray()
                else -> error("unexpected url: $url")
            }
        })

        refresher.refreshGlobal()

        // 同 (channelEpgId, startTime)：后拉取源（B）覆盖前源（A）
        assertEquals(1, store.programs.size)
        assertEquals("新源节目", store.programs.single().title)
    }

    @Test
    fun `multi source merge records warning when one source fails`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://bad.example/xmltv.xml", id = 1, order = 0)
            sources += source("http://good.example/xmltv.xml", id = 2, order = 1)
            channels += channel("ch-2", "CCTV-2 财经")
        }
        val refresher = refresher(store, fetcher = { url ->
            when (url) {
                "http://bad.example/xmltv.xml" -> throw EpgException("连接超时")
                "http://good.example/xmltv.xml" -> sourceBXml.toByteArray()
                else -> error("unexpected url: $url")
            }
        })

        val result = refresher.refreshGlobal()

        // 单源失败不中断：另一源正常写入，警告被记录，刷新状态为成功
        assertEquals(1, result.programsWritten)
        assertEquals(1, store.programs.size)
        assertEquals("cctv2.example", store.programs.single().channelEpgId)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("bad.example"))
        assertNull(refresher.status.snapshot().lastError)
    }

    @Test
    fun `multi source refresh fails when all sources fail`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://bad1.example/xmltv.xml", id = 1, order = 0)
            sources += source("http://bad2.example/xmltv.xml", id = 2, order = 1)
            channels += channel("ch-1", "CCTV-1 综合")
        }
        val refresher = refresher(store, fetcher = { throw EpgException("连接超时") })

        try {
            refresher.refreshGlobal()
            fail("应抛出 EpgException")
        } catch (e: EpgException) {
            assertTrue(e.message.orEmpty().contains("bad2.example"))
        }
        assertTrue(store.programs.isEmpty())
        assertTrue(refresher.status.snapshot().lastError?.contains("连接超时") == true)
    }

    @Test
    fun `refresh skips channels with manual epg binding`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            // 手动绑定 cctv5.example：即使三级匹配到 cctv1.example 也不回写
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv5.example", epgManual = true)
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        refresher.refreshGlobal()

        // 手动绑定频道不被覆盖
        assertTrue(store.epgIdWrites.isEmpty())
        assertEquals("cctv5.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertTrue(store.channels.first { it.id == "ch-1" }.epgManual)
    }

    @Test
    fun `refresh applies match rules and rule wins over auto match`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            // ch-1 无 epgId：三级自动匹配到 cctv1.example，但规则命中优先 → custom.example
            channels += channel("ch-1", "CCTV-1 综合")
            rules += EpgMatchRuleEntity(id = 1, epgChannelId = "custom.example", keyword = "CCTV-1", ruleType = "prefix")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        refresher.refreshGlobal()

        assertEquals("custom.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertEquals("custom.example", store.epgIdWrites.single().second)
        // 规则只覆盖 epgId（epgManual 保持 false），刷新可再次更新
        assertFalse(store.channels.first { it.id == "ch-1" }.epgManual)
    }

    @Test
    fun `refresh rules do not touch channels with existing epgId`() = runTest {
        // 该源只覆盖 cctv5：ch-1（CCTV-1 综合）不会三级匹配到任何 XMLTV id
        val onlyCctv5Xml = """
            <tv>
              <channel id="cctv5.example"><display-name>CCTV-5 体育</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv5.example">
                <title>体育新闻</title>
              </programme>
            </tv>
        """.trimIndent()
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            // ch-1 已有 epgId（此前自动匹配过）：规则名命中但 epgId 非空 → 不覆盖
            channels += channel("ch-1", "CCTV-1 综合", epgId = "existing.example")
            rules += EpgMatchRuleEntity(id = 1, epgChannelId = "custom.example", keyword = "CCTV-1", ruleType = "prefix")
        }
        val refresher = refresher(store, fetcher = { onlyCctv5Xml.toByteArray() })

        refresher.refreshGlobal()

        assertEquals("existing.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertTrue(store.epgIdWrites.isEmpty())
    }

    @Test
    fun `refreshIfStale refreshes when over threshold`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            lastUpdate = nowMillis - 13L * 60 * 60 * 1000 // 13h 前
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        val refreshed = refresher.refreshIfStale()

        assertTrue(refreshed)
        assertEquals(nowMillis, store.lastUpdate)
    }

    @Test
    fun `refreshIfStale skips when fresh`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            lastUpdate = nowMillis - 1L * 60 * 60 * 1000 // 1h 前
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { error("不应拉取") })

        val refreshed = refresher.refreshIfStale()

        assertFalse(refreshed)
    }

    @Test
    fun `refreshIfStale skips when no source configured`() = runTest {
        val store = FakeEpgStore().apply {
            lastUpdate = null // 从未刷新，但也没配置源
        }
        val refresher = refresher(store, fetcher = { error("不应拉取") })

        assertFalse(refresher.refreshIfStale())
    }

    @Test
    fun `refreshIfStale skips when all sources disabled`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml", enabled = false)
            lastUpdate = null
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { error("不应拉取") })

        assertFalse(refresher.refreshIfStale())
    }

    @Test
    fun `shouldAutoRefresh boundary logic`() {
        val threshold = 12L * 60 * 60 * 1000
        val now = 100_000L
        // 从未刷新 → 需要
        assertTrue(EpgRefresher.shouldAutoRefresh(null, now, threshold))
        // 恰好在阈值内（== 阈值）→ 不需要
        assertFalse(EpgRefresher.shouldAutoRefresh(now - threshold, now, threshold))
        // 超过阈值 → 需要
        assertTrue(EpgRefresher.shouldAutoRefresh(now - threshold - 1, now, threshold))
        // 未来时间戳（时钟异常）→ 视为超过阈值，刷新
        assertTrue(EpgRefresher.shouldAutoRefresh(now + 1000, now, threshold))
    }
}
