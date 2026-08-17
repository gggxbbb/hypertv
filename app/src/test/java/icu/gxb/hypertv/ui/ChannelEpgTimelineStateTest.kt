package icu.gxb.hypertv.ui

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.player.Channel
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮层右栏 EPG 时间轴状态单测（纯 JVM，注入 fake programLoader）：
 * - 未匹配 EPG（epgId == null）/ 空频道 → 清空节目与加载态
 * - 窗口计算：以 now 所在小时为中心 ±[WINDOW_CENTER_HOURS]（复用 GuideTimeline 窗口逻辑）
 * - 节目按 startTime 排序
 * - 竞态防护：焦点频道快速切换时过期查询不覆盖新频道
 * - reset 清空状态并令在途查询失效
 */
class ChannelEpgTimelineStateTest {

    private val zone = ZoneOffset.UTC

    private fun ms(hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 8, 17, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun channel(epgId: String? = "epg-1") = Channel(
        id = "c1",
        name = "频道1",
        url = "http://example.com/1.m3u8",
        groupName = "测试组",
        epgId = epgId,
    )

    private fun prog(id: String, start: Long, end: Long) = EpgProgramEntity(
        id = id,
        channelEpgId = "epg-1",
        title = "节目$id",
        description = null,
        startTime = start,
        endTime = end,
        category = null,
    )

    @Test
    fun `loadFor without epg match clears programs and does not call loader`() = runTest {
        var loaderCalled = false
        val state = ChannelEpgTimelineState { _, _, _ ->
            loaderCalled = true
            emptyList()
        }

        state.loadFor(channel(epgId = null), ms(14), zone)

        assertFalse(loaderCalled)
        assertTrue(state.programs.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(ms(11), state.windowStartMs) // 14 点整点 - 3h
    }

    @Test
    fun `loadFor with null channel clears state`() = runTest {
        val state = ChannelEpgTimelineState { _, _, _ -> listOf(prog("a", ms(12), ms(13))) }

        state.loadFor(channel(), ms(14), zone)
        assertTrue(state.programs.isNotEmpty())

        state.loadFor(null, ms(14), zone)

        assertTrue(state.programs.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `loadFor queries window centered on now hour`() = runTest {
        val windows = mutableListOf<Pair<Long, Long>>()
        val state = ChannelEpgTimelineState { _, start, end ->
            windows += start to end
            emptyList()
        }

        state.loadFor(channel(), ms(14, 37), zone)

        val expectedStart = ms(11) // 14 点整点 - 3h
        assertEquals(listOf(expectedStart to (expectedStart + WINDOW_DURATION_MS)), windows)
        assertFalse(state.isLoading)
    }

    @Test
    fun `loadFor sorts programs by start time`() = runTest {
        val state = ChannelEpgTimelineState { _, _, _ ->
            listOf(
                prog("c", ms(14), ms(15)),
                prog("a", ms(11), ms(12)),
                prog("b", ms(12), ms(13)),
            )
        }

        state.loadFor(channel(), ms(14), zone)

        assertEquals(listOf("a", "b", "c"), state.programs.map { it.id })
    }

    @Test
    fun `stale load result does not overwrite newer channel`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<List<EpgProgramEntity>>()
        val state = ChannelEpgTimelineState { epgId, _, _ ->
            if (epgId == "epg-1") {
                firstStarted.complete(Unit)
                firstResult.await()
            } else {
                listOf(prog("b", ms(12), ms(13)))
            }
        }

        // 焦点先落在 epg-1（挂起），随即切到 epg-2 并完成加载
        val job = launch { state.loadFor(channel(epgId = "epg-1"), ms(14), zone) }
        firstStarted.await()
        state.loadFor(channel(epgId = "epg-2"), ms(14), zone)
        assertEquals(listOf("b"), state.programs.map { it.id })

        // epg-1 的过期结果返回后不得覆盖
        firstResult.complete(listOf(prog("stale", ms(12), ms(13))))
        job.join()

        assertEquals(listOf("b"), state.programs.map { it.id })
        assertFalse(state.isLoading)
    }

    @Test
    fun `reset clears state and invalidates in-flight load`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<List<EpgProgramEntity>>()
        val state = ChannelEpgTimelineState { _, _, _ ->
            firstStarted.complete(Unit)
            firstResult.await()
        }

        val job = launch { state.loadFor(channel(), ms(14), zone) }
        firstStarted.await()
        assertTrue(state.isLoading)

        state.reset()
        firstResult.complete(listOf(prog("stale", ms(12), ms(13))))
        job.join()

        assertTrue(state.programs.isEmpty())
        assertFalse(state.isLoading)
    }
}
