package icu.gxb.hypertv.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
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
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cctv1" tvg-logo="http://logo/cctv1.png" group-title="新闻",CCTV-1 高清
        http://stream.example.com/cctv1.m3u8
        #EXTINF:-1 tvg-id="cctv5" group-title="体育",CCTV-5
        http://stream.example.com/cctv5.m3u8
        #EXTGRP:体育
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
    ) = application {
        hypertvModule(
            version = "1.0",
            playlistStore = store,
            urlFetcher = fetcher,
        )
    }

    // ---- /api/playlist/import/preview ----

    @Test
    fun `import preview returns total groups and preview without persisting`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)

        val response = client.post("/api/playlist/import/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = json.decodeFromString<ImportPreview>(response.bodyAsText())
        assertEquals(3, preview.total)
        assertEquals(listOf("新闻", "体育"), preview.groups)
        assertEquals(3, preview.preview.size)
        assertEquals("CCTV-1 高清", preview.preview[0].name)
        assertEquals("http://stream.example.com/cctv1.m3u8", preview.preview[0].url)
        assertEquals("cctv1", preview.preview[0].epgId)
        assertEquals("UTF-8", preview.encoding)
        assertEquals("pl.example.com", preview.sourceName)
        assertEquals("http://pl.example.com/live.m3u", preview.url)
        // 预览不落库
        assertTrue(store.channelsOf("").isEmpty())
    }

    @Test
    fun `import preview with empty url returns 400`() = testApplication {
        hypertvApp()

        val response = client.post("/api/playlist/import/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.decodeFromString<ImportError>(response.bodyAsText())
        assertTrue(error.error.isNotEmpty())
    }

    @Test
    fun `import preview propagates fetch failure as 400`() = testApplication {
        hypertvApp(fetcher = { throw PlaylistImportException("拉取失败：HTTP 404") })

        val response = client.post("/api/playlist/import/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/gone.m3u"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.decodeFromString<ImportError>(response.bodyAsText())
        assertTrue(error.error.contains("404"))
    }

    @Test
    fun `import preview with malformed body returns 400`() = testApplication {
        hypertvApp()

        val response = client.post("/api/playlist/import/preview") {
            contentType(ContentType.Application.Json)
            setBody("""{"not-url": 123}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- /api/playlist/import ----

    @Test
    fun `import persists source and channels incrementally`() = testApplication {
        val store = FakePlaylistImportStore()
        var fetchCount = 0
        hypertvApp(store, fetcher = {
            fetchCount++
            if (fetchCount == 1) sampleM3u.toByteArray() else incrementalM3u.toByteArray()
        })

        // 首次导入：3 条新增
        val first = client.post("/api/playlist/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstResult = json.decodeFromString<ImportResult>(first.bodyAsText())
        assertEquals(3, firstResult.imported)
        assertEquals(0, firstResult.updated)
        assertEquals(0, firstResult.hidden)
        val sourceId = firstResult.sourceId
        assertTrue(sourceId.isNotBlank())
        assertEquals(3, store.channelsOf(sourceId).size)

        // 第二次导入：cctv5 更新、cctv1/cctv6 隐藏（源中消失）、cctv9 新增
        val second = client.post("/api/playlist/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }
        assertEquals(HttpStatusCode.OK, second.status)
        val secondResult = json.decodeFromString<ImportResult>(second.bodyAsText())
        assertEquals(1, secondResult.imported)
        assertEquals(1, secondResult.updated)
        assertEquals(2, secondResult.hidden)
        assertEquals(sourceId, secondResult.sourceId)

        // 同一 URL 复用同一个直播源，未重复建源
        assertEquals(1, store.sources().size)
    }

    @Test
    fun `import empty url returns 400`() = testApplication {
        hypertvApp()

        val response = client.post("/api/playlist/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- /api/playlist/upload ----

    @Test
    fun `upload imports file channels`() = testApplication {
        val store = FakePlaylistImportStore()
        hypertvApp(store)

        val response = client.post("/api/playlist/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", sampleM3u.toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "application/x-mpegURL")
                            append(HttpHeaders.ContentDisposition, "filename=\"channels.m3u\"")
                        })
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = json.decodeFromString<ImportResult>(response.bodyAsText())
        assertEquals(3, result.imported)
        val source = store.sourceById(result.sourceId)
        assertEquals("channels.m3u", source?.name)
        assertEquals("file", source?.type)
        assertEquals(3, store.channelsOf(result.sourceId).size)
    }

    @Test
    fun `upload with empty file returns 400`() = testApplication {
        hypertvApp()

        val response = client.post("/api/playlist/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", ByteArray(0), Headers.build {
                            append(HttpHeaders.ContentType, "application/x-mpegURL")
                            append(HttpHeaders.ContentDisposition, "filename=\"empty.m3u\"")
                        })
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `status route still works alongside import routes`() = testApplication {
        hypertvApp()

        val response = client.get("/api/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val status = json.decodeFromString<ServerStatus>(response.bodyAsText())
        assertEquals("1.0", status.version)
    }
}
