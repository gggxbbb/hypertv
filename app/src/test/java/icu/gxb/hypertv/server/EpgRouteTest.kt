package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.epg.FakeEpgStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPG API 路由契约测试（ticket 09 + v3 多源/规则）：注入内存 [FakeEpgStore] 与
 * [FakeEpgRefreshService]，验证 source 多源 CRUD、规则 CRUD/apply、异步刷新、
 * now/guide/channels 的契约与错误语义。
 */
class EpgRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.epgApp(
        store: FakeEpgStore = FakeEpgStore(),
        refresher: FakeEpgRefreshService = FakeEpgRefreshService(),
    ) = application {
        hypertvModule(
            version = "1.0",
            playlistStore = FakePlaylistImportStore(),
            managementStore = FakeChannelManagementStore(),
            epgStore = store,
            epgRefresher = refresher,
            // 无真实挂起的 fake refresher + Unconfined：POST refresh 的 launch 同步跑完，测试可立即断言
            epgScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun channel(id: String, name: String, epgId: String?, groupName: String = "新闻") = ChannelEntity(
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

    private fun program(id: String, epgId: String, start: Long, end: Long, title: String = "节目 $id") =
        EpgProgramEntity(
            id = id,
            channelEpgId = epgId,
            title = title,
            description = null,
            startTime = start,
            endTime = end,
            category = "综合",
        )

    private fun source(url: String, id: Long = 1, order: Int = 0, enabled: Boolean = true) =
        EpgSourceEntity(id = id, url = url, enabled = enabled, orderIndex = order)

    // ---- 旧 PUT /api/epg/source（单源兼容：清空并设为该单个源）----

    @Test
    fun `legacy put global source replaces all sources with single url`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://old.example.com/x.xml")
        }
        epgApp(store)

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://epg.example.com/global.xml"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val config = json.decodeFromString<EpgSourceConfigDTO>(response.bodyAsText())
        assertEquals(1, config.sources.size)
        assertEquals("http://epg.example.com/global.xml", config.sources[0].url)
        assertEquals(listOf("http://epg.example.com/global.xml"), store.epgSources().map { it.url })
    }

    @Test
    fun `legacy put group source stores group override`() = testApplication {
        val store = FakeEpgStore().apply {
            groups += GroupEntity(name = "体育", orderIndex = 0, isCollapsed = false)
        }
        epgApp(store)

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://epg.example.com/sports.xml","groupId":"体育"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://epg.example.com/sports.xml", store.groupByName("体育")?.epgUrl)
    }

    @Test
    fun `legacy put source with blank url clears all global sources`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://old.example.com/x.xml")
        }
        epgApp(store)

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"  "}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(store.epgSources().isEmpty())
    }

    @Test
    fun `legacy put source for missing group returns 404`() = testApplication {
        epgApp()

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://x.example.com/x.xml","groupId":"不存在"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = json.decodeFromString<ApiError>(response.bodyAsText())
        assertTrue(error.error.contains("不存在"))
    }

    // ---- GET /api/epg/source ----

    @Test
    fun `get source returns sources groups and status`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/global.xml", id = 1)
            sources += source("http://epg.example.com/extra.xml", id = 2, order = 1)
            groups += GroupEntity(name = "新闻", orderIndex = 0, isCollapsed = false)
            groups += GroupEntity(name = "体育", orderIndex = 1, isCollapsed = false, epgUrl = "http://sports.example.com/x.xml")
        }
        epgApp(store)

        val response = client.get("/api/epg/source")

        assertEquals(HttpStatusCode.OK, response.status)
        val config = json.decodeFromString<EpgSourceConfigDTO>(response.bodyAsText())
        assertEquals(2, config.sources.size)
        assertEquals("http://epg.example.com/global.xml", config.sources[0].url)
        assertEquals(true, config.sources[0].enabled)
        assertEquals(2, config.groupSources.size)
        assertEquals("http://sports.example.com/x.xml", config.groupSources.first { it.groupName == "体育" }.url)
        assertNull(config.groupSources.first { it.groupName == "新闻" }.url)
        assertEquals(false, config.status.running)
        assertNull(config.status.lastUpdate)
    }

    // ---- POST /api/epg/source ----

    @Test
    fun `post source appends global source`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://first.example.com/x.xml", id = 1)
        }
        epgApp(store)

        val response = client.post("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://second.example.com/y.xml"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val created = json.decodeFromString<EpgSourceDTO>(response.bodyAsText())
        assertEquals("http://second.example.com/y.xml", created.url)
        assertEquals(true, created.enabled)
        // 追加到末尾，原源保留
        assertEquals(listOf("http://first.example.com/x.xml", "http://second.example.com/y.xml"), store.epgSources().map { it.url })
    }

    @Test
    fun `post source with blank url returns 400`() = testApplication {
        epgApp()

        val response = client.post("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"  "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- PUT /api/epg/source/{id} ----

    @Test
    fun `put source by id updates url and enabled`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/x.xml", id = 7)
        }
        epgApp(store)

        val response = client.put("/api/epg/source/7") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://new.example.com/y.xml","enabled":false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<EpgSourceDTO>(response.bodyAsText())
        assertEquals("http://new.example.com/y.xml", dto.url)
        assertEquals(false, dto.enabled)
        val stored = store.epgSourceById(7)
        assertEquals("http://new.example.com/y.xml", stored?.url)
        assertEquals(false, stored?.enabled)
    }

    @Test
    fun `put source by id missing returns 404`() = testApplication {
        epgApp()

        val response = client.put("/api/epg/source/99") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `put source by id with blank url returns 400`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/x.xml", id = 7)
        }
        epgApp(store)

        val response = client.put("/api/epg/source/7") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- DELETE /api/epg/source/{id} ----

    @Test
    fun `delete source returns 204 and removes it`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/x.xml", id = 7)
        }
        epgApp(store)

        val response = client.delete("/api/epg/source/7")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(store.epgSourceById(7))
    }

    @Test
    fun `delete source missing returns 404`() = testApplication {
        epgApp()

        val response = client.delete("/api/epg/source/99")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- POST /api/epg/refresh ----

    @Test
    fun `post refresh global returns 202 and triggers refresher`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/global.xml")
        }
        val refresher = FakeEpgRefreshService()
        epgApp(store, refresher)

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, refresher.refreshGlobalCalls)
        // 同步 fake：刷新已完成，状态为成功
        assertTrue(!refresher.status.isRunning())
        assertNull(refresher.status.snapshot().lastError)
    }

    @Test
    fun `post refresh group returns 202 and triggers group refresh`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/global.xml")
            groups += GroupEntity(name = "体育", orderIndex = 0, isCollapsed = false, epgUrl = "http://sports.example.com/x.xml")
        }
        val refresher = FakeEpgRefreshService()
        epgApp(store, refresher)

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"体育"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(listOf("体育"), refresher.refreshGroupCalls)
    }

    @Test
    fun `post refresh group falls back to global sources when group has none`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/global.xml")
            groups += GroupEntity(name = "新闻", orderIndex = 0, isCollapsed = false)
        }
        val refresher = FakeEpgRefreshService()
        epgApp(store, refresher)

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"新闻"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(listOf("新闻"), refresher.refreshGroupCalls)
    }

    @Test
    fun `post refresh missing group returns 404`() = testApplication {
        epgApp()

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"不存在"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `post refresh without configured source returns 400`() = testApplication {
        epgApp() // 无全局源、无分组源

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post refresh while running returns 409`() = testApplication {
        val store = FakeEpgStore().apply {
            sources += source("http://epg.example.com/global.xml")
        }
        val refresher = FakeEpgRefreshService().apply { forceRunning() }
        epgApp(store, refresher)

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, refresher.refreshGlobalCalls)
    }

    // ---- 匹配规则 ----

    @Test
    fun `get rules returns list with matched counts`() = testApplication {
        val store = FakeEpgStore().apply {
            rules += EpgMatchRuleEntity(id = 1, epgChannelId = "cctv1.example", keyword = "CCTV-1", ruleType = "prefix")
            rules += EpgMatchRuleEntity(id = 2, epgChannelId = "cctv2.example", keyword = "CCTV-2", ruleType = "contains")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            channels += channel("ch-2", "CCTV-1 HD", epgId = "cctv1.example")
            channels += channel("ch-3", "无", epgId = null)
        }
        epgApp(store)

        val response = client.get("/api/epg/rules")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<EpgRuleDTO>>(response.bodyAsText())
        assertEquals(2, list.size)
        // matchedCount = 当前 epgId == 该 epgChannelId 的频道数
        assertEquals(2, list.first { it.id == 1L }.matchedCount)
        assertEquals(0, list.first { it.id == 2L }.matchedCount)
    }

    @Test
    fun `post rule creates rule`() = testApplication {
        val store = FakeEpgStore()
        epgApp(store)

        val response = client.post("/api/epg/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"epgChannelId":"cctv1.example","keyword":"CCTV-1","ruleType":"prefix"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<EpgRuleDTO>(response.bodyAsText())
        assertEquals("cctv1.example", dto.epgChannelId)
        assertEquals("CCTV-1", dto.keyword)
        assertEquals("prefix", dto.ruleType)
        assertEquals(1, store.matchRules().size)
    }

    @Test
    fun `post rule with invalid ruleType returns 400`() = testApplication {
        epgApp()

        val response = client.post("/api/epg/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"epgChannelId":"cctv1.example","keyword":"CCTV-1","ruleType":"regex"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post rule with blank keyword returns 400`() = testApplication {
        epgApp()

        val response = client.post("/api/epg/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"epgChannelId":"cctv1.example","keyword":"  ","ruleType":"prefix"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `delete rule returns 204 and removes it`() = testApplication {
        val store = FakeEpgStore().apply {
            rules += EpgMatchRuleEntity(id = 5, epgChannelId = "cctv1.example", keyword = "CCTV-1", ruleType = "prefix")
        }
        epgApp(store)

        val response = client.delete("/api/epg/rules/5")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.matchRules().isEmpty())
    }

    @Test
    fun `delete rule missing returns 404`() = testApplication {
        epgApp()

        val response = client.delete("/api/epg/rules/99")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `post rules apply binds matching channels and returns count`() = testApplication {
        val store = FakeEpgStore().apply {
            rules += EpgMatchRuleEntity(id = 1, epgChannelId = "cctv1.example", keyword = "CCTV-1", ruleType = "prefix")
            channels += channel("ch-1", "CCTV-1 综合", epgId = null)
            channels += channel("ch-2", "CCTV-1 HD", epgId = null)
            channels += channel("ch-3", "别的台", epgId = null)
        }
        epgApp(store)

        val response = client.post("/api/epg/rules/apply") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = json.decodeFromString<EpgRuleApplyResult>(response.bodyAsText())
        assertEquals(2, result.applied)
        assertEquals("cctv1.example", store.channels.first { it.id == "ch-1" }.epgId)
        assertEquals("cctv1.example", store.channels.first { it.id == "ch-2" }.epgId)
        assertNull(store.channels.first { it.id == "ch-3" }.epgId)
    }

    // ---- GET /api/epg/channels ----

    @Test
    fun `get epg channels returns catalog with display name and match info`() = testApplication {
        val store = FakeEpgStore().apply {
            epgChannels += EpgChannelEntity(id = "1", displayName = "CCTV1", icon = null)
            epgChannels += EpgChannelEntity(id = "2", displayName = "CCTV2", icon = null)
            epgChannels += EpgChannelEntity(id = "cctv5.example", displayName = "CCTV-5 体育", icon = "http://icons.example.com/5.png")
            channels += channel("ch-1", "CCTV-1 综合", epgId = "1")
            channels += channel("ch-2", "CCTV-1 HD", epgId = "1")
        }
        epgApp(store)

        val response = client.get("/api/epg/channels")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<EpgChannelCandidateDTO>>(response.bodyAsText())
        // 数字 id 按数字序在前，非数字 id 在后
        assertEquals(listOf("1", "2", "cctv5.example"), list.map { it.epgId })
        val cctv1 = list.first { it.epgId == "1" }
        assertEquals("CCTV1", cctv1.displayName)
        assertEquals(2, cctv1.matchedCount)
        assertEquals(setOf("CCTV-1 综合", "CCTV-1 HD"), cctv1.channelNames.toSet())
        val cctv5 = list.first { it.epgId == "cctv5.example" }
        assertEquals("CCTV-5 体育", cctv5.displayName)
        assertEquals("http://icons.example.com/5.png", cctv5.icon)
        assertEquals(0, cctv5.matchedCount)
        assertTrue(cctv5.channelNames.isEmpty())
    }

    @Test
    fun `get epg channels sorts numeric ids numerically`() = testApplication {
        val store = FakeEpgStore().apply {
            epgChannels += EpgChannelEntity(id = "10", displayName = "CCTV10", icon = null)
            epgChannels += EpgChannelEntity(id = "2", displayName = "CCTV2", icon = null)
            epgChannels += EpgChannelEntity(id = "1", displayName = "CCTV1", icon = null)
        }
        epgApp(store)

        val response = client.get("/api/epg/channels")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<EpgChannelCandidateDTO>>(response.bodyAsText())
        assertEquals(listOf("1", "2", "10"), list.map { it.epgId })
    }

    @Test
    fun `get epg channels returns empty when catalog is empty`() = testApplication {
        epgApp() // 无频道目录、无节目

        val response = client.get("/api/epg/channels")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<List<EpgChannelCandidateDTO>>(response.bodyAsText()).isEmpty())
    }

    // ---- GET /api/epg/now ----

    @Test
    fun `get now returns current programs keyed by channel id`() = testApplication {
        val now = System.currentTimeMillis()
        val store = FakeEpgStore().apply {
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            channels += channel("ch-2", "没有节目", epgId = "no-programs")
            channels += channel("ch-3", "无 EPG", epgId = null)
            programs += program("p1", "cctv1.example", start = now - 60_000, end = now + 60_000, title = "正在播")
            programs += program("p2", "no-programs", start = now - 60_000, end = now - 30_000, title = "已结束")
        }
        epgApp(store)

        val response = client.get("/api/epg/now")

        assertEquals(HttpStatusCode.OK, response.status)
        val map = json.decodeFromString<Map<String, EpgProgramDTO>>(response.bodyAsText())
        // 只有覆盖 now 的频道有节目；已结束的 no-programs 与无 epgId 的频道不在结果中
        assertEquals(setOf("ch-1"), map.keys)
        assertEquals("正在播", map["ch-1"]?.title)
    }

    @Test
    fun `get now returns empty map when no channels have epg`() = testApplication {
        val store = FakeEpgStore().apply {
            channels += channel("ch-1", "无 EPG", epgId = null)
        }
        epgApp(store)

        val response = client.get("/api/epg/now")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<Map<String, EpgProgramDTO>>(response.bodyAsText()).isEmpty())
    }

    // ---- GET /api/epg/guide ----

    @Test
    fun `get guide returns sorted programs for channel on date`() = testApplication {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val store = FakeEpgStore().apply {
            channels += channel("ch-1", "CCTV-1 综合", epgId = "cctv1.example")
            programs += program("p1", "cctv1.example", dayStart, dayStart + 30 * 60_000, title = "早间新闻")
            programs += program("p2", "cctv1.example", dayStart + 30 * 60_000, dayStart + 60 * 60_000, title = "午间新闻")
            programs += program("p3", "other.example", dayStart, dayStart + 30 * 60_000, title = "别家台")
        }
        epgApp(store)

        val response = client.get("/api/epg/guide?channelId=ch-1&date=$today")

        assertEquals(HttpStatusCode.OK, response.status)
        val guide = json.decodeFromString<EpgGuideDTO>(response.bodyAsText())
        assertEquals("ch-1", guide.channelId)
        assertEquals(today.toString(), guide.date)
        assertEquals(listOf("早间新闻", "午间新闻"), guide.programs.map { it.title })
        // 按 startTime 升序
        assertTrue(guide.programs.zipWithNext().all { (a, b) -> a.startTime <= b.startTime })
    }

    @Test
    fun `get guide defaults to today and returns empty for no epg channel`() = testApplication {
        val store = FakeEpgStore().apply {
            channels += channel("ch-1", "无 EPG", epgId = null)
        }
        epgApp(store)

        val response = client.get("/api/epg/guide?channelId=ch-1")

        assertEquals(HttpStatusCode.OK, response.status)
        val guide = json.decodeFromString<EpgGuideDTO>(response.bodyAsText())
        assertTrue(guide.programs.isEmpty())
    }

    @Test
    fun `get guide missing channelId returns 400`() = testApplication {
        epgApp()

        val response = client.get("/api/epg/guide")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get guide unknown channel returns 404`() = testApplication {
        epgApp()

        val response = client.get("/api/epg/guide?channelId=nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `get guide invalid date returns 400`() = testApplication {
        val store = FakeEpgStore().apply {
            channels += channel("ch-1", "CCTV-1", epgId = "cctv1")
        }
        epgApp(store)

        val response = client.get("/api/epg/guide?channelId=ch-1&date=2026-13-99")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
