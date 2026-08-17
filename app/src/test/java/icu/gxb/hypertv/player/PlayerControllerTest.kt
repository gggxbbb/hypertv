package icu.gxb.hypertv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlayerController 状态机单测（纯 JVM，注入 fake 播放器/频道源/存储）。
 *
 * 覆盖：play / switchTo 顺序与回绕 / switchToIndex / 开机自动播放（上次频道、
 * 无记录播第一个）/ 播放成功写 last_played_channel_id / 失败重试 3 次计数与
 * 2s 间隔 / 3 次后自动跳下一个频道（ADR-0007）/ 重试期间新指令打断 /
 * 退出停止播放与重开恢复（ticket 02）：stop 复位、resumeIfIdle 恢复上次频道、
 * 空列表与非 Idle 状态静默跳过。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {

    private fun channel(id: String) = Channel(
        id = id,
        name = "频道$id",
        url = url(id),
        groupName = "测试组",
    )

    private fun url(id: String) = "http://example.com/$id.m3u8"

    private class FakePlayer : PlayerOperations {
        val calls = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var listener: PlayerOperations.Listener? = null

        override fun addListener(listener: PlayerOperations.Listener) {
            this.listener = listener
        }

        override fun removeListener(listener: PlayerOperations.Listener) {
            if (this.listener === listener) this.listener = null
        }

        override fun setMediaItem(url: String) {
            calls += "setMediaItem"
            urls += url
        }

        override fun prepare() {
            calls += "prepare"
        }

        override fun play() {
            calls += "play"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }

        fun fireReady() = listener?.onPlayerReady()
        fun fireError() = listener?.onPlayerError()
    }

    private class FakeChannelSource(initial: List<Channel> = emptyList()) : ChannelSource {
        private val flow = MutableStateFlow(initial)
        override val visibleChannels: Flow<List<Channel>> = flow
        fun emit(list: List<Channel>) {
            flow.value = list
        }
    }

    private class FakeGroupSource(initial: List<String> = emptyList()) : GroupSource {
        private val flow = MutableStateFlow(initial)
        override val groups: Flow<List<String>> = flow
        fun emit(list: List<String>) {
            flow.value = list
        }
    }

    private class FakeLastChannelStore : LastChannelStore {
        var lastPlayed: String? = null
        val saved = mutableListOf<String>()
        override suspend fun getLastPlayedChannelId(): String? = lastPlayed
        override suspend fun saveLastPlayedChannelId(channelId: String) {
            saved += channelId
        }
    }

    private fun controller(
        player: FakePlayer,
        source: FakeChannelSource,
        store: FakeLastChannelStore,
        scope: CoroutineScope,
        groups: FakeGroupSource = FakeGroupSource(),
    ) = PlayerController(
        player = player,
        channelSource = source,
        groupSource = groups,
        lastChannelStore = store,
        scope = scope,
    )

    /**
     * 推进 controller 的协程任务：使用 runCurrent 处理 backgroundScope 中当前时刻的
     * 事件（advanceUntilIdle 只处理 foreground 事件，会跳过 backgroundScope 的任务）。
     */
    private fun kotlinx.coroutines.test.TestScope.runController() {
        runCurrent()
    }

    // ---- 开机自动播放 ----

    @Test
    fun `auto start plays last played channel and persists on ready`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore().apply { lastPlayed = "2" }

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()

        // 自动定位到上次频道并开始播放
        assertEquals(url("2"), player.urls.last())
        assertEquals(PlayerState.Preparing("2"), c.state.value)

        // 播放成功（STATE_READY）后写入 last_played_channel_id
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("2"), c.state.value)
        assertEquals(listOf("2"), store.saved)
    }

    @Test
    fun `auto start plays first channel when no last record`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2")))
        val store = FakeLastChannelStore() // 无记录

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()

        assertEquals(url("1"), player.urls.last())
        assertEquals(PlayerState.Preparing("1"), c.state.value)
    }

    @Test
    fun `auto start waits until channels appear`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(emptyList())
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()
        assertEquals(PlayerState.Idle, c.state.value)
        assertTrue(player.calls.isEmpty())

        // 频道出现后自动开始播放
        source.emit(listOf(channel("1"), channel("2")))
        runController()
        assertEquals(url("1"), player.urls.last())
    }

    // ---- play / switchTo / switchToIndex ----

    @Test
    fun `play by channel id switches media item`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // 播放 ch1

        c.play("2")
        assertEquals(url("2"), player.urls.last())
        assertEquals(PlayerState.Preparing("2"), c.state.value)

        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("2"), c.state.value)
        assertEquals(listOf("2"), store.saved)
    }

    @Test
    fun `switchTo moves forward and backward with wrap around`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1

        c.switchTo(1) // ch2
        assertEquals(url("2"), player.urls.last())
        c.switchTo(1) // ch3
        assertEquals(url("3"), player.urls.last())
        c.switchTo(1) // 回绕到 ch1
        assertEquals(url("1"), player.urls.last())
        c.switchTo(-1) // 回绕到 ch3
        assertEquals(url("3"), player.urls.last())
    }

    @Test
    fun `switchToIndex plays absolute index with wrap`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()

        c.switchToIndex(2) // 第 3 个
        assertEquals(url("3"), player.urls.last())
        c.switchToIndex(3) // 超出 → 回绕到 0
        assertEquals(url("1"), player.urls.last())
    }

    // ---- 失败重试与自动跳转（ADR-0007）----

    @Test
    fun `player error retries up to 3 times then auto advances to next channel`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // 播放 ch1

        // 失败 #1：进入重试等待（attempt 1）
        player.fireError()
        assertEquals(PlayerState.ErrorRetrying("1", 1, 3), c.state.value)

        // 2s 未到不重试
        advanceTimeBy(1999)
        assertEquals(1, player.urls.count { it == url("1") })

        // 到达 2s：重试 #1
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, player.urls.count { it == url("1") })
        assertEquals(PlayerState.Preparing("1"), c.state.value)

        // 失败 #2 → 重试 #2
        player.fireError()
        assertEquals(PlayerState.ErrorRetrying("1", 2, 3), c.state.value)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(3, player.urls.count { it == url("1") })

        // 失败 #3 → 重试 #3
        player.fireError()
        assertEquals(PlayerState.ErrorRetrying("1", 3, 3), c.state.value)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(4, player.urls.count { it == url("1") })

        // 失败 #4：重试耗尽 → 自动切下一个频道（ADR-0007）
        player.fireError()
        assertEquals(PlayerState.AutoAdvancing("1", "2"), c.state.value)
        assertEquals(url("2"), player.urls.last())
    }

    @Test
    fun `new command during retry cancels pending retry`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1

        player.fireError()
        assertEquals(PlayerState.ErrorRetrying("1", 1, 3), c.state.value)

        // 观众按上键打断重试
        c.switchTo(-1)
        assertEquals(PlayerState.Preparing("3"), c.state.value)
        assertEquals(url("3"), player.urls.last())

        // 即使时间越过 2s 重试窗口，也不应回放 ch1
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, player.urls.count { it == url("1") })
        assertEquals(PlayerState.Preparing("3"), c.state.value)
    }

    @Test
    fun `single channel gives up after retries exhausted instead of looping`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1

        player.fireError()
        advanceTimeBy(2000)
        runCurrent()
        player.fireError()
        advanceTimeBy(2000)
        runCurrent()
        player.fireError()
        advanceTimeBy(2000)
        runCurrent()
        player.fireError() // 重试耗尽

        // 不无限循环：停止播放
        assertEquals(PlayerState.Idle, c.state.value)
        assertEquals(4, player.urls.count { it == url("1") })
    }

    @Test
    fun `successful playback resets retry counter`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()

        player.fireError()
        advanceTimeBy(2000)
        runCurrent()
        player.fireError() // attempt 2
        advanceTimeBy(2000)
        runCurrent()

        // 第 3 次重试播放成功（READY）→ 重试计数清零
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("1"), c.state.value)

        // 之后再失败，重新从 attempt 1 计
        player.fireError()
        assertEquals(PlayerState.ErrorRetrying("1", 1, 3), c.state.value)
    }

    // ---- 频道列表变化 ----

    @Test
    fun `empty channel list stays idle and commands are no-ops`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(emptyList())
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()

        assertEquals(PlayerState.Idle, c.state.value)
        c.switchTo(1)
        c.play("1")
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun `current channel removed from list auto plays new first channel`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1

        // ch1 被移除（例如直播源被删）
        source.emit(listOf(channel("2"), channel("3")))
        runController()

        assertEquals(url("2"), player.urls.last())
        assertEquals(PlayerState.Preparing("2"), c.state.value)
    }

    // ---- 退出停止播放与重开恢复（ticket 02）----

    @Test
    fun `stop stops playback and resets state to idle`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("1"), c.state.value)

        c.stop()
        assertEquals(PlayerState.Idle, c.state.value)
        assertEquals("stop", player.calls.last())

        // 播放停止后当前频道已清空：再触发错误回调也不应启动任何重试/跳转
        player.fireError()
        runController()
        assertEquals(PlayerState.Idle, c.state.value)
        assertEquals(1, player.calls.count { it == "setMediaItem" })
    }

    @Test
    fun `stop then resumeIfIdle restores last channel playback`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2"), channel("3")))
        val store = FakeLastChannelStore().apply { lastPlayed = "2" }

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch2
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("2"), c.state.value)

        c.stop()
        assertEquals(PlayerState.Idle, c.state.value)

        // 重开 App：恢复播放上次频道
        c.resumeIfIdle()
        runController()
        assertEquals(url("2"), player.urls.last())
        assertEquals(PlayerState.Preparing("2"), c.state.value)

        // 恢复后正常回写上次频道
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("2"), c.state.value)
        assertEquals(listOf("2", "2"), store.saved)
    }

    @Test
    fun `resumeIfIdle skips when channels are empty`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(emptyList())
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController()
        assertEquals(PlayerState.Idle, c.state.value)

        // 冷启动时频道尚未从 Room 发射：静默跳过，不产生播放调用
        c.resumeIfIdle()
        runController()
        assertTrue(player.calls.isEmpty())
        assertEquals(PlayerState.Idle, c.state.value)
    }

    @Test
    fun `resumeIfIdle does nothing when state is not idle`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1"), channel("2")))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope).apply { start() }
        runController() // ch1
        player.fireReady()
        runCurrent()
        assertEquals(PlayerState.Playing("1"), c.state.value)

        val callsBefore = player.calls.size
        c.resumeIfIdle()
        runController()

        // 播放中调用被跳过：无额外播放调用、状态不变
        assertEquals(callsBefore, player.calls.size)
        assertEquals(PlayerState.Playing("1"), c.state.value)
    }

    // ---- 分组列表（ticket 05）----

    @Test
    fun `groups are exposed from group source`() = runTest {
        val player = FakePlayer()
        val source = FakeChannelSource(listOf(channel("1")))
        val groups = FakeGroupSource(listOf("新闻", "体育"))
        val store = FakeLastChannelStore()

        val c = controller(player, source, store, backgroundScope, groups).apply { start() }
        runController()

        assertEquals(listOf("新闻", "体育"), c.groups.value)

        // 分组变化（WebUI 增删分组）实时反映
        groups.emit(listOf("新闻", "体育", "少儿"))
        runController()
        assertEquals(listOf("新闻", "体育", "少儿"), c.groups.value)
    }
}
