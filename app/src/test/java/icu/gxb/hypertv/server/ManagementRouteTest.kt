package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频道/分组管理 API 路由契约测试（ticket 07）。
 * 注入内存 [FakeChannelManagementStore]，验证请求/响应契约与错误语义。
 */
class ManagementRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeChannel(
        id: String,
        name: String = "频道$id",
        group: String = "",
        order: Int = 0,
        hidden: Boolean = false,
        fav: Boolean = false,
        logo: String? = null,
    ) = ChannelEntity(
        id = id,
        sourceId = "src-1",
        name = name,
        url = "http://stream.example.com/$id.m3u8",
        groupName = group,
        logoUrl = logo,
        orderIndex = order,
        isFavorite = fav,
        isHidden = hidden,
        epgId = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 0L,
    )

    private fun ApplicationTestBuilder.hypertvApp(store: FakeChannelManagementStore) = application {
        hypertvModule(
            version = "1.0",
            playlistStore = FakePlaylistImportStore(),
            managementStore = store,
            urlFetcher = { error("不应触发 URL 拉取") },
        )
    }

    // ---- GET /api/channels ----

    @Test
    fun `channels excludes hidden by default`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", "CCTV-1", order = 0))
            seedChannel(makeChannel("b", "CCTV-2", order = 1, hidden = true))
        }
        hypertvApp(store)

        val response = client.get("/api/channels")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<ChannelDTO>>(response.bodyAsText())
        assertEquals(1, list.size)
        assertEquals("CCTV-1", list[0].name)
        assertEquals(1, list[0].number) // orderIndex 0 -> number 1
    }

    @Test
    fun `channels includeHidden true returns hidden channels too`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", "CCTV-1", order = 0))
            seedChannel(makeChannel("b", "CCTV-2", order = 1, hidden = true))
        }
        hypertvApp(store)

        val response = client.get("/api/channels?includeHidden=true")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<ChannelDTO>>(response.bodyAsText())
        assertEquals(2, list.size)
        assertTrue(list.any { it.isHidden })
    }

    @Test
    fun `favorite channels returns only favorites`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", "CCTV-1", order = 0, fav = true))
            seedChannel(makeChannel("b", "CCTV-2", order = 1))
        }
        hypertvApp(store)

        val response = client.get("/api/channels/favorites")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<ChannelDTO>>(response.bodyAsText())
        assertEquals(1, list.size)
        assertEquals("CCTV-1", list[0].name)
    }

    // ---- PUT /api/channels/{id} ----

    @Test
    fun `update channel renames and moves group`() = testApplication {
        val store = FakeChannelManagementStore().apply { seedChannel(makeChannel("a", "CCTV-1", order = 0)) }
        hypertvApp(store)

        val response = client.put("/api/channels/a") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"CCTV-1 高清","groupName":"体育","logoUrl":"http://logo/c1.png","isHidden":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<ChannelDTO>(response.bodyAsText())
        assertEquals("CCTV-1 高清", dto.name)
        assertEquals("体育", dto.groupName)
        assertEquals("http://logo/c1.png", dto.logoUrl)
        assertTrue(dto.isHidden)
        assertEquals(1, dto.number)
    }

    @Test
    fun `update channel clears logo with empty string`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", "CCTV-1", order = 0, logo = "http://logo/old.png"))
        }
        hypertvApp(store)

        val response = client.put("/api/channels/a") {
            contentType(ContentType.Application.Json)
            setBody("""{"logoUrl":""}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<ChannelDTO>(response.bodyAsText())
        assertNull(dto.logoUrl)
    }

    @Test
    fun `update missing channel returns 404`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.put("/api/channels/nope") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = json.decodeFromString<ApiError>(response.bodyAsText())
        assertTrue(error.error.isNotEmpty())
    }

    @Test
    fun `update with malformed body returns 400`() = testApplication {
        val store = FakeChannelManagementStore().apply { seedChannel(makeChannel("a")) }
        hypertvApp(store)

        val response = client.put("/api/channels/a") {
            contentType(ContentType.Application.Json)
            setBody("""{"not-a-field": 1}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- DELETE /api/channels/{id} ----

    @Test
    fun `delete channel returns 204 and removes it`() = testApplication {
        val store = FakeChannelManagementStore().apply { seedChannel(makeChannel("a")) }
        hypertvApp(store)

        val response = client.delete("/api/channels/a")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(store.channel("a"))
    }

    @Test
    fun `delete missing channel returns 404`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.delete("/api/channels/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- POST /api/channels/reorder ----

    @Test
    fun `reorder channels applies new order`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", order = 0))
            seedChannel(makeChannel("b", order = 1))
            seedChannel(makeChannel("c", order = 2))
        }
        hypertvApp(store)

        val response = client.post("/api/channels/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["c","a","b"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val order = store.channelList().map { it.id }
        assertEquals(listOf("c", "a", "b"), order)
    }

    @Test
    fun `reorder keeps unlisted channels at end in relative order`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedChannel(makeChannel("a", order = 0))
            seedChannel(makeChannel("b", order = 1))
            seedChannel(makeChannel("c", order = 2))
        }
        hypertvApp(store)

        val response = client.post("/api/channels/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["c"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val order = store.channelList().map { it.id }
        assertEquals(listOf("c", "a", "b"), order)
    }

    // ---- POST /api/channels/{id}/favorite ----

    @Test
    fun `favorite toggles channel`() = testApplication {
        val store = FakeChannelManagementStore().apply { seedChannel(makeChannel("a", fav = false)) }
        hypertvApp(store)

        val response = client.post("/api/channels/a/favorite") {
            contentType(ContentType.Application.Json)
            setBody("""{"favorite":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(store.channel("a")!!.isFavorite)
    }

    @Test
    fun `favorite missing channel returns 404`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.post("/api/channels/nope/favorite") {
            contentType(ContentType.Application.Json)
            setBody("""{"favorite":true}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- GET /api/groups ----

    @Test
    fun `groups returns list with channel counts`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
            seedGroup(GroupEntity("新闻", 1, false))
            seedChannel(makeChannel("a", group = "体育"))
            seedChannel(makeChannel("b", group = "体育"))
            seedChannel(makeChannel("c", group = "新闻"))
        }
        hypertvApp(store)

        val response = client.get("/api/groups")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<GroupDTO>>(response.bodyAsText())
        assertEquals(2, list.size)
        assertEquals("体育", list[0].name)
        assertEquals(2, list[0].channelCount)
        assertEquals(1, list[1].channelCount)
    }

    // ---- POST /api/groups ----

    @Test
    fun `create group appends to end`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
        }
        hypertvApp(store)

        val response = client.post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"新闻"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<GroupDTO>(response.bodyAsText())
        assertEquals("新闻", dto.name)
        assertEquals(1, dto.orderIndex)
    }

    @Test
    fun `create duplicate group returns 400`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
        }
        hypertvApp(store)

        val response = client.post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"体育"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.decodeFromString<ApiError>(response.bodyAsText())
        assertTrue(error.error.contains("体育"))
    }

    @Test
    fun `rename group moves channels to new name`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
            seedChannel(makeChannel("a", group = "体育"))
        }
        hypertvApp(store)

        val response = client.post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"体育","newName":"体育频道"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<GroupDTO>(response.bodyAsText())
        assertEquals("体育频道", dto.name)
        assertEquals(1, dto.channelCount)
        assertEquals("体育频道", store.channel("a")!!.groupName)
        assertNull(store.groupList().firstOrNull { it.name == "体育" })
    }

    @Test
    fun `rename missing group returns 404`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.post("/api/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"不存在","newName":"新名"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- DELETE /api/groups/{name} ----

    @Test
    fun `delete group clears channels group name`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
            seedChannel(makeChannel("a", group = "体育"))
        }
        hypertvApp(store)

        val response = client.delete("/api/groups/体育")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(store.groupList().firstOrNull { it.name == "体育" })
        assertEquals("", store.channel("a")!!.groupName)
    }

    @Test
    fun `delete missing group returns 404`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.delete("/api/groups/不存在")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- POST /api/groups/reorder ----

    @Test
    fun `reorder groups applies new order`() = testApplication {
        val store = FakeChannelManagementStore().apply {
            seedGroup(GroupEntity("体育", 0, false))
            seedGroup(GroupEntity("新闻", 1, false))
            seedGroup(GroupEntity("少儿", 2, false))
        }
        hypertvApp(store)

        val response = client.post("/api/groups/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"names":["少儿","体育","新闻"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("少儿", "体育", "新闻"), store.groupList().map { it.name })
    }

    @Test
    fun `status route still works alongside management routes`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.get("/api/status")

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().isBlank())
    }

    // ---- 空态（ticket 11 错误处理边界复核）：无数据时必须 200 空集合，不 500 ----

    @Test
    fun `channels returns 200 empty array when no data`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.get("/api/channels")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<List<ChannelDTO>>(response.bodyAsText()).isEmpty())
    }

    @Test
    fun `favorites returns 200 empty array when none`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.get("/api/channels/favorites")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<List<ChannelDTO>>(response.bodyAsText()).isEmpty())
    }

    @Test
    fun `groups returns 200 empty array when no groups`() = testApplication {
        hypertvApp(FakeChannelManagementStore())

        val response = client.get("/api/groups")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<List<GroupDTO>>(response.bodyAsText()).isEmpty())
    }
}
