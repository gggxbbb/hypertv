package icu.gxb.hypertv.ui

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EpgGuideState 状态机单测（纯 JVM，ticket 10）：
 * - 打开：窗口默认当前整点往前 1h（总长 4h）、焦点首行、只加载前 GUIDE_PAGE_SIZE 个频道
 * - 上下键移动行焦点（两端钳制、底部翻页追加）
 * - 左右键移动时间窗口（±1 小时）
 * - 关闭清理节目缓存
 */
class EpgGuideStateTest {

    private val zone = ZoneOffset.UTC

    private fun ms(hour: Int) =
        ZonedDateTime.of(2026, 8, 17, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    // ---- 打开 ----

    @Test
    fun `open centers window on current hour and loads first page`() {
        val guide = EpgGuideState()
        guide.open(ms(14), totalChannels = 120)

        assertTrue(guide.isOpen)
        assertEquals(0, guide.focusedRow)
        assertEquals(GUIDE_PAGE_SIZE, guide.loadedChannelCount)
        assertEquals(ms(13), guide.windowStartMs) // 14 - 1
    }

    @Test
    fun `open with fewer channels than page size loads all`() {
        val guide = EpgGuideState()
        guide.open(ms(14), totalChannels = 10)

        assertEquals(10, guide.loadedChannelCount)
    }

    @Test
    fun `open with no channels stays idle`() {
        val guide = EpgGuideState()
        guide.open(ms(14), totalChannels = 0)

        assertTrue(guide.isOpen)
        assertEquals(0, guide.loadedChannelCount)
        assertFalse(guide.moveFocus(1, 0))
    }

    // ---- 行焦点与翻页 ----

    @Test
    fun `move focus steps within loaded page`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 120) }

        guide.moveFocus(1, 120)
        assertEquals(1, guide.focusedRow)
        guide.moveFocus(1, 120)
        assertEquals(2, guide.focusedRow)
        assertEquals(50, guide.loadedChannelCount) // 未到底部不翻页

        guide.moveFocus(-1, 120)
        assertEquals(1, guide.focusedRow)
    }

    @Test
    fun `move focus clamps at top`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 120) }

        assertFalse(guide.moveFocus(-1, 120))
        assertEquals(0, guide.focusedRow)
    }

    @Test
    fun `moving past bottom of loaded page appends next page`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 120) }

        // 一直按到本页底部（index 49）
        repeat(49) { guide.moveFocus(1, 120) }
        assertEquals(49, guide.focusedRow)

        // 再向下：追加下一页，焦点停在原行
        assertTrue(guide.moveFocus(1, 120))
        assertEquals(49, guide.focusedRow)
        assertEquals(100, guide.loadedChannelCount)

        // 新页内可继续移动
        guide.moveFocus(1, 120)
        assertEquals(50, guide.focusedRow)
    }

    @Test
    fun `moving past bottom when no more channels clamps without append`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 50) }
        repeat(49) { guide.moveFocus(1, 50) }

        assertFalse(guide.moveFocus(1, 50))
        assertEquals(49, guide.focusedRow)
        assertEquals(50, guide.loadedChannelCount)
    }

    @Test
    fun `last page appends remaining channels not full page`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 80) }
        repeat(49) { guide.moveFocus(1, 80) }

        assertTrue(guide.moveFocus(1, 80)) // 追加 30（剩余）而不是 50
        assertEquals(80, guide.loadedChannelCount)
    }

    // ---- 窗口移动 ----

    @Test
    fun `move window shifts start by one hour each direction`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 10) }
        val start = guide.windowStartMs

        guide.moveWindow(1)
        assertEquals(start + HOUR_MS, guide.windowStartMs)

        guide.moveWindow(-1)
        assertEquals(start, guide.windowStartMs)
    }

    // ---- 关闭 ----

    @Test
    fun `close clears programs and isOpen`() {
        val guide = EpgGuideState().apply { open(ms(14), totalChannels = 10) }
        guide.setPrograms(mapOf("epg-1" to emptyList()))

        guide.close()

        assertFalse(guide.isOpen)
        assertTrue(guide.programsByChannel.isEmpty())
    }
}
