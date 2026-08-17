package icu.gxb.hypertv.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import icu.gxb.hypertv.data.db.HypertvDatabase
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.m3u.EncodingDetector
import icu.gxb.hypertv.m3u.M3uParser
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入分组同步回归测试（数据一致性 bug）：
 * 导入流程解析出的分组必须落 groups 表，电视端分组标签与 WebUI 分组管理页
 * 读的都是 groups 表（/api/groups、GroupDao.getAll），此前导入只写 channels 表导致为空。
 *
 * 使用真实 Repository + in-memory Room，覆盖：
 * - 导入后 groups 表包含解析出的全部分组
 * - 重复导入同一源不重复创建分组
 * - 用户已配置属性（orderIndex/isCollapsed/epgUrl）不被导入覆盖
 * - 无分组源不新增分组
 * - 路由级复现：POST /api/playlist/import 后 GET /api/groups 有数据
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistImportGroupSyncTest {

    private lateinit var db: HypertvDatabase
    private lateinit var repository: HypertvRepository

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HypertvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HypertvRepository(
            channelDao = db.channelDao(),
            groupDao = db.groupDao(),
            playlistSourceDao = db.playlistSourceDao(),
            epgProgramDao = db.epgProgramDao(),
            appConfigDao = db.appConfigDao(),
            epgSourceDao = db.epgSourceDao(),
            epgMatchRuleDao = db.epgMatchRuleDao(),
            epgChannelDao = db.epgChannelDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun importer(fetcher: suspend (String) -> ByteArray) = PlaylistImporter(
        parser = M3uParser(),
        encodingDetector = EncodingDetector,
        store = HypertvPlaylistImportStore(repository),
        fetchUrl = fetcher,
    )

    /** 含"新闻""体育"两个分组 + 一个无分组频道的 M3U。 */
    private val groupedM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cctv1" group-title="新闻",CCTV-1 高清
        http://stream.example.com/cctv1.m3u8
        #EXTINF:-1 tvg-id="cctv5" group-title="体育",CCTV-5
        http://stream.example.com/cctv5.m3u8
        #EXTINF:-1 tvg-id="cctv6",CCTV-6
        http://stream.example.com/cctv6.m3u8
    """.trimIndent()

    // ---- 单元级：真实 Repository + in-memory Room ----

    @Test
    fun `import syncs parsed groups into groups table`() = runTest {
        importer { groupedM3u.toByteArray() }.importUrl("http://pl.example.com/live.m3u")

        val groups = repository.groupsOnce()
        assertEquals(listOf("新闻", "体育"), groups.map { it.name })
        // 新分组按出现顺序追加：orderIndex 0、1 起，isCollapsed 默认 false，epgUrl 默认 null
        assertEquals(listOf(0, 1), groups.map { it.orderIndex })
        assertTrue(groups.all { !it.isCollapsed && it.epgUrl == null })
    }

    @Test
    fun `reimporting same source does not duplicate groups`() = runTest {
        val imp = importer { groupedM3u.toByteArray() }
        imp.importUrl("http://pl.example.com/live.m3u")
        imp.importUrl("http://pl.example.com/live.m3u")

        val groups = repository.groupsOnce()
        // 同一 name 只一条，且不重排
        assertEquals(listOf("新闻", "体育"), groups.map { it.name })
        assertEquals(listOf(0, 1), groups.map { it.orderIndex })
    }

    @Test
    fun `existing group keeps user configured properties on import`() = runTest {
        // 用户手动创建的分组：已配置排序、折叠与分组级 EPG
        repository.upsertGroup(
            GroupEntity(name = "新闻", orderIndex = 5, isCollapsed = true, epgUrl = "http://epg.example.com/news.xml"),
        )

        importer { groupedM3u.toByteArray() }.importUrl("http://pl.example.com/live.m3u")

        val news = repository.groupByNameOnce("新闻")
        assertEquals(5, news?.orderIndex)
        assertEquals(true, news?.isCollapsed)
        assertEquals("http://epg.example.com/news.xml", news?.epgUrl)
        // 已存在分组跳过，只有新出现的分组被追加（max 5 + 1 = 6）
        val groups = repository.groupsOnce()
        assertEquals(listOf("新闻", "体育"), groups.map { it.name })
        assertEquals(listOf(5, 6), groups.map { it.orderIndex })
    }

    @Test
    fun `import without groups does not add groups`() = runTest {
        val bareM3u = """
            #EXTM3U
            #EXTINF:-1,裸频道 1
            http://stream.example.com/naked1.m3u8
        """.trimIndent()

        importer { bareM3u.toByteArray() }.importUrl("http://pl.example.com/live.m3u")

        assertTrue(repository.groupsOnce().isEmpty())
    }

    // ---- 路由级：复现 bug（导入后 /api/groups 应为空数组）----

    @Test
    fun `groups api returns synced groups after import`() = testApplication {
        application {
            hypertvModule(
                version = "1.0",
                playlistStore = HypertvPlaylistImportStore(repository),
                managementStore = HypertvChannelManagementStore(repository),
                urlFetcher = { groupedM3u.toByteArray() },
            )
        }

        val importResponse = client.post("/api/playlist/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"http://pl.example.com/live.m3u"}""")
        }
        assertEquals(HttpStatusCode.OK, importResponse.status)

        val groupsResponse = client.get("/api/groups")
        assertEquals(HttpStatusCode.OK, groupsResponse.status)
        val groups = json.decodeFromString<List<GroupDTO>>(groupsResponse.bodyAsText())
        assertEquals(listOf("新闻", "体育"), groups.map { it.name })
        // 分组级频道计数与分组标签一致
        assertEquals(1, groups[0].channelCount)
        assertEquals(1, groups[1].channelCount)
    }
}
