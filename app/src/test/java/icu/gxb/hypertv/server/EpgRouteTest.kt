package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.epg.FakeEpgStore
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
 * EPG API 路由契约测试（ticket 09）：注入内存 [FakeEpgStore] 与
 * [FakeEpgRefreshService]，验证 source 配置、异步刷新、now/guide 的契约与错误语义。
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

    // ---- PUT /api/epg/source ----

    @Test
    fun `put global source stores url and returns config`() = testApplication {
        val store = FakeEpgStore()
        epgApp(store)

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://epg.example.com/global.xml"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val config = json.decodeFromString<EpgSourceConfigDTO>(response.bodyAsText())
        assertEquals("http://epg.example.com/global.xml", config.globalUrl)
        assertEquals("http://epg.example.com/global.xml", store.globalSourceUrl)
    }

    @Test
    fun `put group source stores group override`() = testApplication {
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
    fun `put source with blank url clears global`() = testApplication {
        val store = FakeEpgStore().apply { globalSourceUrl = "http://old.example.com/x.xml" }
        epgApp(store)

        val response = client.put("/api/epg/source") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"  "}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(store.globalSourceUrl?.takeIf { it.isNotBlank() })
    }

    @Test
    fun `put source for missing group returns 404`() = testApplication {
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
    fun `get source returns global groups and status`() = testApplication {
        val store = FakeEpgStore().apply {
            globalSourceUrl = "http://epg.example.com/global.xml"
            groups += GroupEntity(name = "新闻", orderIndex = 0, isCollapsed = false)
            groups += GroupEntity(name = "体育", orderIndex = 1, isCollapsed = false, epgUrl = "http://sports.example.com/x.xml")
        }
        epgApp(store)

        val response = client.get("/api/epg/source")

        assertEquals(HttpStatusCode.OK, response.status)
        val config = json.decodeFromString<EpgSourceConfigDTO>(response.bodyAsText())
        assertEquals("http://epg.example.com/global.xml", config.globalUrl)
        assertEquals(2, config.groups.size)
        assertEquals("http://sports.example.com/x.xml", config.groups.first { it.name == "体育" }.epgUrl)
        assertNull(config.groups.first { it.name == "新闻" }.epgUrl)
        assertEquals(false, config.status.running)
        assertNull(config.status.lastUpdate)
    }

    // ---- POST /api/epg/refresh ----

    @Test
    fun `post refresh global returns 202 and triggers refresher`() = testApplication {
        val store = FakeEpgStore().apply { globalSourceUrl = "http://epg.example.com/global.xml" }
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
            globalSourceUrl = "http://epg.example.com/global.xml"
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
    fun `post refresh group falls back to global source when group has none`() = testApplication {
        val store = FakeEpgStore().apply {
            globalSourceUrl = "http://epg.example.com/global.xml"
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
        val store = FakeEpgStore().apply { globalSourceUrl = "http://epg.example.com/global.xml" }
        val refresher = FakeEpgRefreshService().apply { forceRunning() }
        epgApp(store, refresher)

        val response = client.post("/api/epg/refresh") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, refresher.refreshGlobalCalls)
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
