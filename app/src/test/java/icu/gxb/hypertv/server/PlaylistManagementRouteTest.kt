package icu.gxb.hypertv.server

import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多源管理 API 路由契约测试（ticket 08）。
 * 注入内存 [FakePlaylistImportStore] 与 fake 文件读写，验证
 * 列表/重命名/删除级联/刷新增量合并/上传同源匹配的契约与错误语义。
 */
class PlaylistManagementRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cctv1" group-title="新闻",CCTV-1
        http://stream.example.com/cctv1.m3u8
        #EXTINF:-1 tvg-id="cctv5" group-title="体育",CCTV-5
        http://stream.example.com/cctv5.m3u8
        #EXTINF:-1 tvg-id="cctv6",CCTV-6
        http://stream.example.com/cctv6.m3u8
    """.trimIndent()

    /** 第二次导入的源：cctv5 更名、cctv1/cctv6 消失、新增 cctv9 */
    private val incrementalM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cctv5" group-title="体育",CCTV-5 高清
        http://stream.example.com/cctv5.m3u8
        #EXTINF:-1 tvg-id="cctv9" group-title="新闻",CCTV-9
        http://stream.example.com/cctv9.m3u8
    """.trimIndent()

    private fun ApplicationTestBuilder.hypertvApp(
        store: FakePlaylistImportStore = FakePlaylistImportStore(),
        fetcher: suspend (String) -> ByteArray = { sampleM3u.toByteArray() },
        saveFile: suspend (String, ByteArray) -> String = { id, _ -> "/tmp/uploads/$id.m3u" },
        readFile: suspend (String) -> ByteArray = { sampleM3u.toByteArray() },
    ) = application {
        hypertvModule(
            version = "1.0",
            playlistStore = store,
            managementStore = FakeChannelManagementStore(),
            urlFetcher = fetcher,
            saveFile = saveFile,
            readFile = readFile,
        )
    }

    private fun uploadBody(fileName: String, content: String, sourceName: String? = null) =
        MultiPartFormDataContent(
            formData {
                if (sourceName != null) append("sourceName", sourceName)
                append("file", content.toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "application/x-mpegURL")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        )

    // ---- GET /api/playlists ----

    @Test
    fun `playlists returns sources with channel counts`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)
        // 预置一个 URL 源（含 3 条频道）
        client.importFirstSource()

        val response = client.get("/api/playlists")

        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<PlaylistDTO>>(response.bodyAsText())
        assertEquals(1, list.size)
        val dto = list[0]
        assertEquals("url", dto.type)
        assertEquals("pl.example.com", dto.name)
        assertEquals(3, dto.channelCount)
        assertTrue(dto.lastImportedAt > 0)
    }

    @Test
    fun `playlists returns empty list when no sources`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)

        val response = client.get("/api/playlists")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, json.decodeFromString<List<PlaylistDTO>>(response.bodyAsText()).size)
    }

    // ---- PUT /api/playlists/{id} ----

    @Test
    fun `rename source returns updated dto`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)
        val sourceId = client.importFirstSource()

        val response = client.put("/api/playlists/$sourceId") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"我的直播源"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = json.decodeFromString<PlaylistDTO>(response.bodyAsText())
        assertEquals("我的直播源", dto.name)
        assertEquals(3, dto.channelCount)
    }

    @Test
    fun `rename with blank name returns 400`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)
        val sourceId = client.importFirstSource()

        val response = client.put("/api/playlists/$sourceId") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `rename missing source returns 404`() = testApplication {
        hypertvApp()

        val response = client.put("/api/playlists/nope") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"新名"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- DELETE /api/playlists/{id} ----

    @Test
    fun `delete source cascades to its channels`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)
        val sourceId = client.importFirstSource()
        assertEquals(3, store.channelsOf(sourceId).size)

        val response = client.delete("/api/playlists/$sourceId")

        assertEquals(HttpStatusCode.NoContent, response.status)
        // 级联删除该源全部频道（含收藏记录），ADR-0004
        assertEquals(0, store.channelsOf(sourceId).size)
        assertNull(store.sourceById(sourceId))
    }

    @Test
    fun `delete missing source returns 404`() = testApplication {
        hypertvApp()

        val response = client.delete("/api/playlists/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- POST /api/playlists/{id}/refresh ----

    @Test
    fun `refresh url source re-imports and returns merge counts`() = testApplication {
        val store = FakePlaylistImportStore()
        var fetchCount = 0
        hypertvApp(store, fetcher = {
            fetchCount++
            if (fetchCount == 1) sampleM3u.toByteArray() else incrementalM3u.toByteArray()
        })
        val sourceId = client.importFirstSource()

        val response = client.post("/api/playlists/$sourceId/refresh")

        assertEquals(HttpStatusCode.OK, response.status)
        val result = json.decodeFromString<ImportResult>(response.bodyAsText())
        assertEquals(1, result.imported)
        assertEquals(1, result.updated)
        assertEquals(2, result.hidden)
        assertEquals(sourceId, result.sourceId)
        assertEquals(1, store.sources().size) // 复用同一源，未新建
    }

    @Test
    fun `refresh file source reads persisted content`() = testApplication {
        val store = FakePlaylistImportStore()
        val savedBytes = mutableMapOf<String, ByteArray>()
        var readContent = sampleM3u
        hypertvApp(
            store,
            saveFile = { id, bytes ->
                savedBytes[id] = bytes
                "/tmp/uploads/$id.m3u"
            },
            readFile = { readContent.toByteArray() },
        )
        val upload = client.post("/api/playlist/upload") {
            setBody(uploadBody("tv.m3u", sampleM3u, sourceName = "电视源"))
        }
        val first = json.decodeFromString<ImportResult>(upload.bodyAsText())
        assertEquals(3, first.imported)
        // 文件源保存了落盘路径
        val savedSource = store.sourceById(first.sourceId)!!
        assertEquals("/tmp/uploads/${first.sourceId}.m3u", savedSource.url)

        // 文件内容变化后 refresh：增量合并计数
        readContent = incrementalM3u
        val response = client.post("/api/playlists/${first.sourceId}/refresh")

        assertEquals(HttpStatusCode.OK, response.status)
        val result = json.decodeFromString<ImportResult>(response.bodyAsText())
        assertEquals(1, result.imported)
        assertEquals(1, result.updated)
        assertEquals(2, result.hidden)
    }

    @Test
    fun `refresh missing source returns 404`() = testApplication {
        hypertvApp()

        val response = client.post("/api/playlists/nope/refresh")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `refresh file source with missing file returns 400`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(
            store,
            saveFile = { _, _ -> "/tmp/uploads/ghost.m3u" },
            readFile = { throw java.io.FileNotFoundException("/tmp/uploads/ghost.m3u") },
        )
        val upload = client.post("/api/playlist/upload") {
            setBody(uploadBody("ghost.m3u", sampleM3u, sourceName = "幽灵源"))
        }
        val sourceId = json.decodeFromString<ImportResult>(upload.bodyAsText()).sourceId

        val response = client.post("/api/playlists/$sourceId/refresh")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.decodeFromString<ImportError>(response.bodyAsText())
        assertTrue(error.error.isNotEmpty())
    }

    // ---- 预览增量预测（冲突提示）----

    @Test
    fun `url preview predicts incremental merge when source exists`() = testApplication {
        val store = FakePlaylistImportStore()
        var fetchCount = 0
        hypertvApp(store, fetcher = {
            fetchCount++
            // 第 1 次拉取（import）用 sample，第 2 次（preview）用 incremental
            if (fetchCount == 1) sampleM3u.toByteArray() else incrementalM3u.toByteArray()
        })
        client.importFirstSource()

        val response = client.post("/api/playlist/import/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = json.decodeFromString<ImportPreview>(response.bodyAsText())
        assertEquals(1, preview.imported)
        assertEquals(1, preview.updated)
        assertEquals(2, preview.hidden)
        assertEquals(3, preview.existingChannelCount)
    }

    @Test
    fun `upload preview predicts merge counts for matching sourceName`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store, fetcher = { error("不应触发 URL 拉取") })
        // 先导入同源（sourceName=电视源），内容为 sample
        client.post("/api/playlist/upload") {
            setBody(uploadBody("channels.m3u", sampleM3u, sourceName = "电视源"))
        }

        // 预览上传新内容（incremental）：应预测 updated=1, hidden=2, imported=1
        val response = client.post("/api/playlist/upload/preview") {
            setBody(uploadBody("channels.m3u", incrementalM3u, sourceName = "电视源"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = json.decodeFromString<ImportPreview>(response.bodyAsText())
        assertEquals(1, preview.imported)
        assertEquals(1, preview.updated)
        assertEquals(2, preview.hidden)
        assertEquals(3, preview.existingChannelCount)
        // 预览不落库
        assertEquals(1, store.sources().size)
        assertEquals(3, store.channelsOf(store.sources().first().id).size)
    }

    // ---- POST /api/playlist/upload/preview ----

    @Test
    fun `upload preview returns parse preview without persisting`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)

        val response = client.post("/api/playlist/upload/preview") {
            setBody(uploadBody("channels.m3u", sampleM3u, sourceName = "电视源"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = json.decodeFromString<ImportPreview>(response.bodyAsText())
        assertEquals(3, preview.total)
        assertEquals(listOf("新闻", "体育"), preview.groups)
        assertEquals("UTF-8", preview.encoding)
        assertEquals("电视源", preview.sourceName)
        assertNull(preview.imported) // 无匹配源时不预测
        // 预览不落库
        assertEquals(0, store.sources().size)
    }

    // ---- POST /api/playlist/upload 同源匹配 ----

    @Test
    fun `upload with same sourceName reuses source id and merges`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store, fetcher = { error("不应触发 URL 拉取") })

        val first = client.post("/api/playlist/upload") {
            setBody(uploadBody("channels.m3u", sampleM3u, sourceName = "电视源"))
        }
        val firstResult = json.decodeFromString<ImportResult>(first.bodyAsText())
        assertEquals(3, firstResult.imported)

        // 再次上传相同 sourceName：复用同一文件源做增量合并
        val second = client.post("/api/playlist/upload") {
            setBody(uploadBody("channels.m3u", incrementalM3u, sourceName = "电视源"))
        }
        val secondResult = json.decodeFromString<ImportResult>(second.bodyAsText())

        assertEquals(firstResult.sourceId, secondResult.sourceId)
        assertEquals(1, secondResult.imported)
        assertEquals(1, secondResult.updated)
        assertEquals(2, secondResult.hidden)
        assertEquals(1, store.sources().size)
    }

    @Test
    fun `upload without sourceName always creates new source`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)

        val first = client.post("/api/playlist/upload") {
            setBody(uploadBody("channels.m3u", sampleM3u))
        }
        val second = client.post("/api/playlist/upload") {
            setBody(uploadBody("channels.m3u", incrementalM3u))
        }
        val firstId = json.decodeFromString<ImportResult>(first.bodyAsText()).sourceId
        val secondId = json.decodeFromString<ImportResult>(second.bodyAsText()).sourceId

        // 无 sourceName 时保持 03 语义：每次上传视为新源
        assertTrue(firstId != secondId)
        assertEquals(2, store.sources().size)
    }

    // ---- helpers ----

    /** 导入一个 URL 源（sampleM3u），返回 sourceId。 */
    private suspend fun io.ktor.client.HttpClient.importFirstSource(): String {
        val response = post("/api/playlist/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString<ImportResult>(response.bodyAsText()).sourceId
    }
}
