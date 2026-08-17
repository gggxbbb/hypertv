package icu.gxb.hypertv.server

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存 PortStore fake：记录写入次数与最后写入值，get 返回可配置的初始值。 */
private class FakePortStore(private var initial: Int? = null) : PortStore {
    var saved: Int? = null
    var putCount = 0

    override suspend fun get(): Int? = initial

    override suspend fun put(port: Int) {
        putCount++
        saved = port
    }
}

/** 固定返回值的 Random：覆盖 nextInt(from, until)，让随机端口完全可控。 */
private class FixedRandom(private val value: Int) : Random() {
    override fun nextBits(bitCount: Int): Int = value

    override fun nextInt(from: Int, until: Int): Int = value
}

/**
 * 动态端口选择器单测（动态端口改造）：
 * 保存端口优先复用 / 无保存时随机范围 / 保存端口绑定失败转随机重试 /
 * 重试次数上限 / 成功持久化 / 全部失败返回失败 / 持久化写入失败不影响功能。
 */
class ServerPortManagerTest {

    @Test
    fun `reuses saved port when it binds successfully`() = runTest {
        val store = FakePortStore(initial = 50000)
        val attempts = mutableListOf<Int>()
        val manager = ServerPortManager(store, FixedRandom(60000))

        val result = manager.acquirePort { candidate ->
            attempts.add(candidate)
            true
        }

        assertEquals(50000, result)
        // 保存端口优先尝试且仅尝试一次，不进入随机
        assertEquals(listOf(50000), attempts)
        // bind 成功即写回（幂等），持久化保存的正是实际端口
        assertEquals(50000, store.saved)
    }

    @Test
    fun `generates random port in dynamic range when none saved`() = runTest {
        val store = FakePortStore(initial = null)
        val manager = ServerPortManager(store, Random(42))

        val result = manager.acquirePort { true }

        assertTrue("端口 $result 应在 49152..65535 内", result in ServerPortManager.MIN_PORT..ServerPortManager.MAX_PORT)
        assertEquals(result, store.saved)
    }

    @Test
    fun `falls back to random port when saved port fails to bind`() = runTest {
        val store = FakePortStore(initial = 50000)
        val attempts = mutableListOf<Int>()
        val manager = ServerPortManager(store, FixedRandom(55000))

        val result = manager.acquirePort { candidate ->
            attempts.add(candidate)
            candidate != 50000 // 已保存端口被占用，其余端口可绑定
        }

        assertEquals(55000, result)
        assertEquals(listOf(50000, 55000), attempts)
        assertEquals(55000, store.saved)
    }

    @Test
    fun `respects max random attempts and returns null on total failure`() = runTest {
        val store = FakePortStore(initial = null)
        var attempts = 0
        val manager = ServerPortManager(store, FixedRandom(55555), maxRandomAttempts = 3)

        val result = manager.acquirePort { candidate ->
            attempts++
            false
        }

        assertNull(result)
        assertEquals(3, attempts)
        assertNull(store.saved)
        assertEquals(0, store.putCount)
    }

    @Test
    fun `saved port failure plus exhausted retries returns null`() = runTest {
        val store = FakePortStore(initial = 50000)
        var attempts = 0
        val manager = ServerPortManager(store, FixedRandom(50001), maxRandomAttempts = 2)

        val result = manager.acquirePort {
            attempts++
            false
        }

        assertNull(result)
        // 1 次保存端口 + 2 次随机重试
        assertEquals(3, attempts)
        assertEquals(0, store.putCount)
    }

    @Test
    fun `persists chosen port on success`() = runTest {
        val store = FakePortStore(initial = null)
        val manager = ServerPortManager(store, FixedRandom(60000))

        val result = manager.acquirePort { true }

        assertEquals(60000, result)
        assertEquals(1, store.putCount)
        assertEquals(60000, store.saved)
    }

    @Test
    fun `persistence write failure does not break successful bind`() = runTest {
        val store = object : PortStore {
            override suspend fun get(): Int? = null

            override suspend fun put(port: Int) = error("写入失败（如 Room 异常）")
        }
        val manager = ServerPortManager(store, FixedRandom(60000))

        val result = manager.acquirePort { true }

        // 端口已成功监听，存储写失败不影响本次功能（下次启动重新随机）
        assertEquals(60000, result)
    }
}
