package icu.gxb.hypertv.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import icu.gxb.hypertv.data.db.HypertvDatabase
import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import icu.gxb.hypertv.data.repository.HypertvRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据层测试：Room in-memory + Robolectric，覆盖 DAO CRUD、
 * 按 URL 查询、批量 reorder、收藏/隐藏、级联删除、Flow 发射、app_config。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HypertvRepositoryTest {

    private lateinit var db: HypertvDatabase
    private lateinit var repository: HypertvRepository

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
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- helpers ----

    private fun source(id: String = "src-1", name: String = "源 1") = PlaylistSourceEntity(
        id = id,
        name = name,
        type = "url",
        url = "http://example.com/playlist.m3u",
        lastImportedAt = 100L,
        createdAt = 50L,
    )

    private fun channel(
        id: String = "ch-1",
        sourceId: String = "src-1",
        name: String = "频道 1",
        url: String = "http://example.com/1.m3u8",
        orderIndex: Int = 0,
    ) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        url = url,
        groupName = "新闻",
        logoUrl = null,
        orderIndex = orderIndex,
        epgId = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        createdAt = 100L,
    )

    private fun epg(
        id: String = "p-1",
        channelEpgId: String = "epg-1",
        start: Long = 1000L,
        end: Long = 2000L,
    ) = EpgProgramEntity(
        id = id,
        channelEpgId = channelEpgId,
        title = "新闻联播",
        description = null,
        startTime = start,
        endTime = end,
        category = null,
    )

    /** 插入频道前先保证其所属直播源存在（外键约束要求） */
    private suspend fun addChannelsWithSources(channels: List<ChannelEntity>) {
        channels.map { it.sourceId }.distinct().forEach { sourceId ->
            repository.upsertPlaylistSource(source(sourceId))
        }
        repository.addChannels(channels)
    }

    // ---- 频道 CRUD ----

    @Test
    fun `channel upsert update and delete`() = runTest {
        addChannelsWithSources(listOf(channel()))

        val inserted = repository.channels.first()
        assertEquals(1, inserted.size)
        assertEquals("频道 1", inserted[0].name)

        val renamed = inserted[0].copy(name = "改名频道")
        repository.updateChannel(renamed)
        assertEquals("改名频道", repository.channels.first()[0].name)

        repository.deleteChannel(renamed.id)
        assertTrue(repository.channels.first().isEmpty())
    }

    @Test
    fun `find channel by url for incremental merge`() = runTest {
        addChannelsWithSources(listOf(channel(url = "http://example.com/1.m3u8")))

        val found = repository.channelByUrl("http://example.com/1.m3u8")
        assertEquals("ch-1", found?.id)

        assertNull(repository.channelByUrl("http://example.com/not-exist.m3u8"))
    }

    @Test
    fun `batch reorder updates orderIndex`() = runTest {
        addChannelsWithSources(
            listOf(
                channel("a", orderIndex = 0),
                channel("b", orderIndex = 1),
                channel("c", orderIndex = 2),
            ),
        )

        repository.reorderChannels(listOf("c" to 0, "b" to 1, "a" to 2))

        val ordered = repository.channels.first()
        assertEquals(listOf("c", "b", "a"), ordered.map { it.id })
        assertEquals(listOf(0, 1, 2), ordered.map { it.orderIndex })
    }

    @Test
    fun `set favorite and hidden flags`() = runTest {
        addChannelsWithSources(listOf(channel("a"), channel("b")))

        repository.setChannelFavorite("a", true)
        repository.setChannelHidden("b", true)

        val favorites = repository.favoriteChannels.first()
        assertEquals(listOf("a"), favorites.map { it.id })

        val hidden = repository.channelById("b").first()
        assertTrue(hidden?.isHidden == true)
        // 收藏列表不含隐藏项逻辑之外的普通过滤：b 未收藏
        assertTrue(favorites.none { it.id == "b" })
    }

    @Test
    fun `channels flow emits after mutation`() = runTest {
        assertTrue(repository.channels.first().isEmpty())

        addChannelsWithSources(listOf(channel("a"), channel("b")))

        val afterInsert = repository.channels.first { it.size == 2 }
        assertEquals(listOf("a", "b"), afterInsert.map { it.id })

        repository.deleteChannel("a")
        val afterDelete = repository.channels.first { it.size == 1 }
        assertEquals(listOf("b"), afterDelete.map { it.id })
    }

    // ---- 分组 ----

    @Test
    fun `group upsert reorder and delete`() = runTest {
        repository.upsertGroup(GroupEntity("新闻", 0, false))
        repository.upsertGroup(GroupEntity("体育", 1, false))
        repository.upsertGroup(GroupEntity("电影", 2, false))

        repository.reorderGroups(listOf("电影" to 0, "体育" to 1, "新闻" to 2))

        val ordered = repository.groups.first()
        assertEquals(listOf("电影", "体育", "新闻"), ordered.map { it.name })

        repository.deleteGroup("体育")
        assertEquals(listOf("电影", "新闻"), repository.groups.first().map { it.name })
    }

    // ---- 直播源与级联删除 ----

    @Test
    fun `deleting playlist source cascades to its channels`() = runTest {
        repository.upsertPlaylistSource(source("src-1"))
        repository.upsertPlaylistSource(source("src-2", name = "源 2"))
        addChannelsWithSources(
            listOf(
                channel("a", sourceId = "src-1"),
                channel("b", sourceId = "src-1"),
                channel("c", sourceId = "src-2"),
            ),
        )

        repository.deletePlaylistSource("src-1")

        assertNull(repository.playlistSourceById("src-1"))
        // src-1 的频道全部级联删除，src-2 的保留
        val remaining = repository.channels.first()
        assertEquals(listOf("c"), remaining.map { it.id })
    }

    // ---- EPG ----

    @Test
    fun `epg upsert query window and expired cleanup`() = runTest {
        repository.upsertPrograms(
            listOf(
                epg("p1", "epg-1", start = 1000L, end = 2000L),
                epg("p2", "epg-1", start = 2000L, end = 3000L),
            ),
        )

        // 时间窗口 [900, 2000) 内与 p1 有交集（p2 从 2000 开始，不含）
        val overlapping = repository.programs("epg-1", 900L, 2000L).first()
        assertEquals(listOf("p1"), overlapping.map { it.id })

        // 窗口外无节目
        val outside = repository.programs("epg-1", 5000L, 6000L).first()
        assertTrue(outside.isEmpty())

        // 其他频道不串台
        assertTrue(repository.programs("epg-2", 0L, 9999L).first().isEmpty())

        // 过期清理（endTime < 2500，删除 p1 保留 p2）
        repository.deleteExpiredPrograms(2500L)
        val remaining = repository.programs("epg-1", 0L, 9999L).first()
        assertEquals(listOf("p2"), remaining.map { it.id })
    }

    // ---- 应用配置 ----

    @Test
    fun `app config put get and overwrite`() = runTest {
        assertNull(repository.getConfig("epg.source"))

        repository.putConfig("epg.source", "http://example.com/xmltv.xml")
        assertEquals("http://example.com/xmltv.xml", repository.getConfig("epg.source"))

        repository.putConfig("epg.source", "http://example.com/new.xml")
        assertEquals("http://example.com/new.xml", repository.getConfig("epg.source"))
    }
}
