package icu.gxb.hypertv.ui

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Info 浮层状态机单测（纯 JVM，ticket 10）：
 * - Info 键开合（toggle）与再次按 Info 收起
 * - 当前节目注入与清除
 * - 超时收起（onTimeout）状态迁移
 * （"当前节目查询"的筛选逻辑在 GuideTimelineTest.findCurrentProgram 覆盖）
 */
class InfoOverlayStateTest {

    private fun prog(id: String) = EpgProgramEntity(
        id = id,
        channelEpgId = "epg-1",
        title = "新闻联播",
        description = "简介",
        startTime = 1000L,
        endTime = 2000L,
        category = null,
    )

    @Test
    fun `info key toggles open and closed`() {
        val state = InfoOverlayState()

        state.toggle()
        assertTrue(state.isOpen)

        state.toggle() // 再次按 Info 收起
        assertFalse(state.isOpen)
    }

    @Test
    fun `open clears previous program and update injects result`() {
        val state = InfoOverlayState()
        state.open()
        state.updateProgram(prog("p1"))
        assertEquals("p1", state.program?.id)

        // 换台后重新打开：先清空，等待新查询
        state.close()
        assertFalse(state.isOpen)
        assertNull(state.program)
    }

    @Test
    fun `setProgram null clears program`() {
        val state = InfoOverlayState().apply { open(); updateProgram(prog("p1")) }

        state.updateProgram(null)

        assertNull(state.program)
    }

    @Test
    fun `timeout closes overlay`() {
        val state = InfoOverlayState().apply { open(); updateProgram(prog("p1")) }

        state.onTimeout()

        assertFalse(state.isOpen)
        assertNull(state.program)
    }

    @Test
    fun `open resets program before query`() {
        val state = InfoOverlayState().apply { open(); updateProgram(prog("p1")) }
        state.close()

        state.open()
        assertTrue(state.isOpen)
        assertNull(state.program)
    }
}
