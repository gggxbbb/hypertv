package icu.gxb.hypertv.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModuleTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `api status returns 200 with version ip port`() = testApplication {
        application {
            hypertvModule(version = "9.9-test", ipProvider = { "192.168.1.10" })
        }

        val response = client.get("/api/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val status = json.decodeFromString<ServerStatus>(response.bodyAsText())
        assertEquals("9.9-test", status.version)
        assertEquals("192.168.1.10", status.ip)
        assertEquals(SERVER_PORT, status.port)
    }

    @Test
    fun `api status allows null ip when unavailable`() = testApplication {
        application {
            hypertvModule(version = "1.0", ipProvider = { null })
        }

        val response = client.get("/api/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val status = json.decodeFromString<ServerStatus>(response.bodyAsText())
        assertEquals(null, status.ip)
        assertEquals(SERVER_PORT, status.port)
    }

    @Test
    fun `root path serves WebUI index html`() = testApplication {
        application {
            hypertvModule(version = "1.0", indexHtml = { "<h1>HyperTV WebUI 占位页</h1>" })
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("HyperTV WebUI"))
    }

    @Test
    fun `root path falls back to built-in placeholder when asset missing`() = testApplication {
        application {
            hypertvModule(version = "1.0")
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("HyperTV WebUI"))
    }
}
