package icu.gxb.hypertv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FavoriteStore 收藏状态机单测（纯 JVM，注入 fake 数据源）。
 *
 * 覆盖：toggle 收藏/取消收藏并返回目标状态、Room Flow 反映新列表、
 * 空收藏列表、收藏列表顺序保持、快速连续切换不依赖 Flow 发射时机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteStoreTest {

    private fun channel(id: String) = Channel(
        id = id,
        name = "频道$id",
        url = "http://example.com/$id.m3u8",
        groupName = "测试组",
        orderIndex = id.toInt(),
    )

    /**
     * fake 数据源：setFavorite 模拟 Room 写库（可配置延迟以放大 Flow 未到达的窗口），
     * 写库后立即重新发射收藏列表，模拟 Room Flow 失效刷新。
     */
    private class FakeFavoriteDataSource(initial: List<Channel> = emptyList()) : FavoriteDataSource {
        private val flow = MutableStateFlow(initial)
        override val favoriteChannels: Flow<List<Channel>> = flow

        val writes = mutableListOf<Pair<String, Boolean>>()
        var writeDelayMs: Long = 0

        override suspend fun setFavorite(channelId: String, isFavorite: Boolean) {
            writes += channelId to isFavorite
            if (writeDelayMs > 0) delay(writeDelayMs)
            val toggled = Channel(
                id = channelId,
                name = "频道$channelId",
                url = "http://example.com/$channelId.m3u8",
                groupName = "测试组",
                orderIndex = channelId.toIntOrNull() ?: 0,
            )
            flow.value = flow.value
                .filterNot { it.id == channelId }
                .plus(if (isFavorite) listOf(toggled) else emptyList())
                .sortedBy { it.orderIndex }
        }

        fun emit(list: List<Channel>) {
            flow.value = list
        }
    }

    private fun store(dataSource: FavoriteDataSource, scope: CoroutineScope) =
        FavoriteStore(dataSource, scope)

    @Test
    fun `toggle favorites a channel writes and flow reflects it`() = runTest {
        val ds = FakeFavoriteDataSource()
        val s = store(ds, backgroundScope)
        runCurrent()

        assertFalse(s.isFavorite("1"))
        val nowFavorite = s.toggle("1")
        runCurrent() // 处理 Flow 发射的收藏列表

        assertTrue(nowFavorite)
        assertTrue(s.isFavorite("1"))
        assertEquals(listOf("1"), s.favorites.value.map { it.id })
        assertEquals(listOf("1" to true), ds.writes)
    }

    @Test
    fun `toggle unfavorites a channel and flow reflects it`() = runTest {
        val ds = FakeFavoriteDataSource(listOf(channel("1")))
        val s = store(ds, backgroundScope)
        runCurrent()

        assertTrue(s.isFavorite("1"))
        val nowFavorite = s.toggle("1")
        runCurrent()

        assertFalse(nowFavorite)
        assertFalse(s.isFavorite("1"))
        assertTrue(s.favorites.value.isEmpty())
        assertEquals(listOf("1" to false), ds.writes)
    }

    @Test
    fun `empty favorites list stays empty until toggled`() = runTest {
        val ds = FakeFavoriteDataSource()
        val s = store(ds, backgroundScope)
        runCurrent()

        assertEquals(emptyList<Channel>(), s.favorites.value)
        assertFalse(s.isFavorite("x"))

        assertTrue(s.toggle("x"))
        runCurrent()
        assertEquals(listOf("x"), s.favorites.value.map { it.id })
    }

    @Test
    fun `favorites order follows data source order`() = runTest {
        val ds = FakeFavoriteDataSource(listOf(channel("2"), channel("1"), channel("3")))
        val s = store(ds, backgroundScope)
        runCurrent()

        assertEquals(listOf("2", "1", "3"), s.favorites.value.map { it.id })
    }

    @Test
    fun `rapid toggles keep state consistent while flow is stale`() = runTest {
        val ds = FakeFavoriteDataSource(listOf(channel("1"))).apply { writeDelayMs = 100 }
        val s = store(ds, backgroundScope)
        runCurrent()

        assertTrue(s.isFavorite("1"))

        // 两次快速切换（Flow 尚未发射第一笔写的结果）：
        // 第一次取消、第二次再收藏，镜像保证不依赖 Flow 的异步时机
        val first = s.toggle("1")
        val second = s.toggle("1")

        assertFalse(first)
        assertTrue(second)
        assertEquals(listOf("1" to false, "1" to true), ds.writes)

        // 推进时间让写库与 Flow 刷新完成，最终状态与目标一致
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("1"), s.favorites.value.map { it.id })
        assertTrue(s.isFavorite("1"))
    }

    @Test
    fun `external flow emission replaces local mirror`() = runTest {
        val ds = FakeFavoriteDataSource(listOf(channel("1")))
        val s = store(ds, backgroundScope)
        runCurrent()
        assertTrue(s.isFavorite("1"))

        // WebUI 侧改动（ticket 07 起）：数据源直接发射新列表，store 镜像随之重建
        ds.emit(listOf(channel("2")))
        runCurrent()
        assertFalse(s.isFavorite("1"))
        assertTrue(s.isFavorite("2"))
        assertEquals(listOf("2"), s.favorites.value.map { it.id })
    }
}
