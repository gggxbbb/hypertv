package icu.gxb.hypertv.ui

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guide 时间轴纯逻辑单测（ticket 10）：
 * - 节目条 x/width 布局（窗口内 / 边界裁剪 / 无交集）
 * - 窗口小时对齐与移动（±1 小时，打开默认当前整点往前 1h、总长 4h）
 * - 当前节目查询（正在播放 / 节目间隙 / 边界时刻）
 * - 时间格式化（HH:mm）
 */
class GuideTimelineTest {

    private val zone = ZoneOffset.UTC

    private fun ms(hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 8, 17, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val windowStart = ms(13) // 13:00
    private val windowEnd = windowStart + WINDOW_DURATION_MS // 17:00

    private fun prog(id: String, start: Long, end: Long) = EpgProgramEntity(
        id = id,
        channelEpgId = "epg-1",
        title = "节目$id",
        description = null,
        startTime = start,
        endTime = end,
        category = null,
    )

    // ---- 节目条布局 ----

    @Test
    fun `layout full program inside window maps position and width`() {
        val layout = layoutProgram(windowStart, windowEnd, ms(14), ms(15), 600f)
        assertNotNull(layout)
        assertEquals(150f, layout!!.x, 0.01f) // 1/4 × 600
        assertEquals(150f, layout.width, 0.01f)
    }

    @Test
    fun `layout program straddling window start is clipped to window`() {
        val layout = layoutProgram(windowStart, windowEnd, ms(12), ms(14), 600f)
        assertNotNull(layout)
        assertEquals(0f, layout!!.x, 0.01f)
        assertEquals(150f, layout.width, 0.01f) // 可见区间 [13:00, 14:00)
    }

    @Test
    fun `layout program straddling window end is clipped to window`() {
        val layout = layoutProgram(windowStart, windowEnd, ms(16), ms(18), 600f)
        assertNotNull(layout)
        assertEquals(450f, layout!!.x, 0.01f)
        assertEquals(150f, layout.width, 0.01f) // 可见区间 [16:00, 17:00)
    }

    @Test
    fun `layout program fully covering window fills whole grid`() {
        val layout = layoutProgram(windowStart, windowEnd, ms(8), ms(20), 600f)
        assertNotNull(layout)
        assertEquals(0f, layout!!.x, 0.01f)
        assertEquals(600f, layout.width, 0.01f)
    }

    @Test
    fun `layout program with no overlap returns null`() {
        assertNull(layoutProgram(windowStart, windowEnd, ms(8), ms(10), 600f)) // 窗口前
        assertNull(layoutProgram(windowStart, windowEnd, ms(18), ms(20), 600f)) // 窗口后
        assertNull(layoutProgram(windowStart, windowEnd, ms(12), ms(12), 600f)) // 零时长
    }

    @Test
    fun `layout zero width grid returns null`() {
        assertNull(layoutProgram(windowStart, windowEnd, ms(12), ms(13), 0f))
    }

    // ---- 窗口对齐与移动 ----

    @Test
    fun `alignToHour rounds down to hour boundary`() {
        assertEquals(ms(14), alignToHour(ms(14, 37), zone))
        assertEquals(ms(0), alignToHour(ms(0, 1), zone))
    }

    @Test
    fun `guide window start is current hour minus 1 hour`() {
        assertEquals(ms(13), guideWindowStartFor(ms(14, 37), zone)) // 14 - 1
        // 跨天回绕：02:05 → 02:00 整点 - 1h = 01:00
        assertEquals(ms(1), guideWindowStartFor(ms(2, 5), zone))
    }

    @Test
    fun `move window shifts by step hours`() {
        assertEquals(ms(12), moveGuideWindow(ms(11), 1))
        assertEquals(ms(10), moveGuideWindow(ms(11), -1))
        assertEquals(ms(17), moveGuideWindow(ms(11), 6))
    }

    // ---- 当前节目查询 ----

    @Test
    fun `findCurrentProgram returns program containing now`() {
        val programs = listOf(prog("a", ms(10), ms(12)), prog("b", ms(12), ms(14)), prog("c", ms(14), ms(16)))
        assertEquals("b", findCurrentProgram(programs, ms(12, 30))?.id)
        assertEquals("a", findCurrentProgram(programs, ms(10, 0))?.id) // 开始时刻包含
    }

    @Test
    fun `findCurrentProgram at program end boundary does not match`() {
        val programs = listOf(prog("a", ms(10), ms(12)), prog("b", ms(12), ms(14)))
        // now == 12:00，节目 a 已结束（endTime 不包含），节目 b 从 12:00 开始
        assertEquals("b", findCurrentProgram(programs, ms(12, 0))?.id)
    }

    @Test
    fun `findCurrentProgram returns null in gap or empty list`() {
        val programs = listOf(prog("a", ms(10), ms(12)), prog("b", ms(14), ms(16)))
        assertNull(findCurrentProgram(programs, ms(13, 0))) // 间隙
        assertNull(findCurrentProgram(emptyList(), ms(13, 0)))
        assertNull(findCurrentProgram(programs, ms(8, 0))) // 最早节目开始前
    }

    // ---- 时间格式化 ----

    @Test
    fun `format functions use local hour and minute`() {
        assertEquals("14:00", formatTime(ms(14), zone))
        assertEquals("14:37", formatTime(ms(14, 37), zone))
        assertEquals("11:00", formatHourTick(ms(11), zone))
        assertEquals("13:00 - 17:00", formatWindowRange(windowStart, WINDOW_DURATION_MS, zone))
    }
}
