package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgChannelEntity
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
        epgMatchSource: String? = null,
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
        epgMatchSource = epgMatchSource,
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
        // 频道 epgId + 来源已回写：ch-1 本就有相等 epgId 但补写 level1 来源，ch-2 从 null 补全（level3），ch-3 未匹配不动
        assertEquals(listOf("ch-1" to "cctv1.example", "ch-2" to "cctv5.example"), store.epgIdWrites)
        assertEquals("cctv5.example", store.channels.first { it.id == "ch-2" }.epgId)
        assertEquals("level1", store.channels.first { it.id == "ch-1" }.epgMatchSource)
        assertEquals("level3", store.channels.first { it.id == "ch-2" }.epgMatchSource)
        assertNull(store.channels.first { it.id == "ch-3" }.epgId)
        assertNull(store.channels.first { it.id == "ch-3" }.epgMatchSource)
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
            // 手动绑定 cctv5.example：即使三级匹配到 cctv1.example 也不回写，来源保持 manual
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv5.example", epgManual = true, epgMatchSource = "manual")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        refresher.refreshGlobal()

        // 手动绑定频道不被覆盖
        assertTrue(store.epgIdWrites.isEmpty())
        assertEquals("cctv5.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertTrue(store.channels.first { it.id == "ch-1" }.epgManual)
        assertEquals("manual", store.channels.first { it.id == "ch-1" }.epgMatchSource)
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
        // 规则命中写来源 rule（覆盖自动匹配的 level 来源）；epgManual 保持 false，刷新可再次更新
        assertEquals("rule", store.channels.first { it.id == "ch-1" }.epgMatchSource)
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
    fun `refresh overwrites previous rule source with auto level source`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            // ch-1 此前由规则绑定（source=rule）；本次刷新 name 匹配到 cctv1.example → 自动来源覆盖旧规则来源
            channels += channel("ch-1", "CCTV-1 综合", epgId = "rule.example", epgMatchSource = "rule")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        refresher.refreshGlobal()

        // 自动匹配覆盖旧的规则绑定（epgId + 来源原子更新），来源 = 本次生效的 level3
        assertEquals("cctv1.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertEquals("level3", store.channels.first { it.id == "ch-1" }.epgMatchSource)
    }

    @Test
    fun `refresh writes level5 source for contains match`() = runTest {
        val xml = """
            <tv>
              <channel id="fengyun.example"><display-name>风云剧场</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="fengyun.example">
                <title>剧场节目</title>
              </programme>
            </tv>
        """.trimIndent()
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-风云剧场")
        }
        val refresher = refresher(store, fetcher = { xml.toByteArray() })

        val result = refresher.refreshGlobal()

        assertEquals("fengyun.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertEquals("level5", store.channels.first { it.id == "ch-1" }.epgMatchSource)
        assertEquals(1, result.stats.level5)
        assertEquals(1, result.stats.matched)
        assertEquals(listOf("fengyun.example"), store.programs.map { it.channelEpgId })
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

    @Test
    fun `gzip encoded source is decompressed before parsing`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/e1.xml.gz")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val gzipped = gzip(xmltvXml)
        assertTrue(gzipped.size > 2 && gzipped[0] == 0x1f.toByte() && gzipped[1] == 0x8b.toByte())
        val refresher = refresher(store, fetcher = { gzipped })

        val result = refresher.refreshGlobal()

        // 只有 ch-1 匹配（cctv1.example），xmltvXml 中该频道 1 条节目
        assertEquals(1, result.programsWritten)
        assertEquals("cctv1.example", store.programs.single().channelEpgId)
    }

    @Test
    fun `plain xml source is not corrupted by gunzip`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/e.xml.gz")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { xmltvXml.toByteArray() })

        val result = refresher.refreshGlobal()

        assertEquals(1, result.programsWritten)
    }

    // ---- v4：EPG 频道目录持久化 ----

    @Test
    fun `refresh upserts epg channel catalog with display name and icon`() = runTest {
        val xml = """
            <tv>
              <channel id="cctv1.example">
                <display-name>CCTV-1 综合</display-name>
                <icon src="http://icons.example.com/1.png"/>
              </channel>
              <channel id="cctv2.example"><display-name>CCTV-2 财经</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
                <title>新闻联播</title>
              </programme>
            </tv>
        """.trimIndent()
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        val refresher = refresher(store, fetcher = { xml.toByteArray() })

        refresher.refreshGlobal()

        // 合并结果的全部 XMLTV 频道进目录（含无 icon 的），id 为 XMLTV 频道 id
        val catalog = store.epgChannels
        assertEquals(2, catalog.size)
        val cctv1 = catalog.first { it.id == "cctv1.example" }
        assertEquals("CCTV-1 综合", cctv1.displayName)
        assertEquals("http://icons.example.com/1.png", cctv1.icon)
        val cctv2 = catalog.first { it.id == "cctv2.example" }
        assertEquals("CCTV-2 财经", cctv2.displayName)
        assertNull(cctv2.icon)
    }

    @Test
    fun `repeated refresh overwrites epg channel catalog idempotently`() = runTest {
        val firstXml = """
            <tv>
              <channel id="cctv1.example"><display-name>CCTV-1 综合</display-name>
                <icon src="http://icons.example.com/a.png"/></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
                <title>新闻联播</title>
              </programme>
            </tv>
        """.trimIndent()
        val secondXml = """
            <tv>
              <channel id="cctv1.example"><display-name>CCTV-1 综合频道</display-name>
                <icon src="http://icons.example.com/b.png"/></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
                <title>新闻联播</title>
              </programme>
            </tv>
        """.trimIndent()
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
        }
        var content = firstXml
        val refresher = refresher(store, fetcher = { content.toByteArray() })

        refresher.refreshGlobal()
        content = secondXml
        refresher.refreshGlobal()

        // 幂等覆盖：同 id 只有一条，displayName/icon 取最近一次
        assertEquals(1, store.epgChannels.size)
        val updated = store.epgChannels.single()
        assertEquals("CCTV-1 综合频道", updated.displayName)
        assertEquals("http://icons.example.com/b.png", updated.icon)
    }

    @Test
    fun `fetch failure does not touch epg channel catalog`() = runTest {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/xmltv.xml")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            epgChannels += EpgChannelEntity(id = "keep.example", displayName = "保留", icon = null)
        }
        val refresher = refresher(store, fetcher = { throw EpgException("连接超时") })

        try {
            refresher.refreshGlobal()
            fail("应抛出 EpgException")
        } catch (_: EpgException) {
        }

        // 拉取失败不写目录：既有目录保留
        assertEquals(1, store.epgChannels.size)
        assertEquals("keep.example", store.epgChannels.single().id)
    }

    /** gzip 压缩工具：模拟 .gz 源返回的压缩字节 */
    private fun gzip(text: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        return bos.toByteArray()
    }
}
