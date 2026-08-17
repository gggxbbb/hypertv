package icu.gxb.hypertv.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 数字键输入状态机单测（ticket 05，spec 7.2）。
 * 覆盖：数字累积、超长截断、OK 确认、2s 超时自动跳转、按键重置超时窗口、
 * 清空取消挂起超时、超时后只触发一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelNumberControllerTest {

    @Test
    fun `digits accumulate across presses`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        c.onDigit(1)
        c.onDigit(2)
        c.onDigit(3)
        assertEquals("123", c.digits.value)
    }

    @Test
    fun `input longer than max digits keeps newest digits`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        listOf(1, 2, 3, 4, 5, 6).forEach { c.onDigit(it) }
        // MAX_DIGITS = 4：顶掉最旧两位
        assertEquals("3456", c.digits.value)
    }

    @Test
    fun `ok confirms with accumulated number and clears`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        var jumped: Int? = null
        c.onJump = { jumped = it }

        c.onDigit(4)
        c.onDigit(2)
        c.confirm()

        assertEquals(42, jumped)
        assertEquals("", c.digits.value)
    }

    @Test
    fun `ok with no input does nothing`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        var jumped: Int? = null
        c.onJump = { jumped = it }

        c.confirm()

        assertNull(jumped)
    }

    @Test
    fun `no jump before 2 seconds timeout`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        var jumped: Int? = null
        c.onJump = { jumped = it }

        c.onDigit(7)
        advanceTimeBy(1_999)
        runCurrent()

        assertNull(jumped)
    }

    @Test
    fun `timeout after 2 seconds without key jumps automatically`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        var jumped: Int? = null
        c.onJump = { jumped = it }

        c.onDigit(7)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(7, jumped)
        assertEquals("", c.digits.value)
    }

    @Test
    fun `typing resets the timeout window`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        val jumps = mutableListOf<Int>()
        c.onJump = { jumps += it }

        c.onDigit(1)
        advanceTimeBy(1_999)
        c.onDigit(2) // 重置 2s 窗口
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(emptyList<Int>(), jumps) // 尚未触发

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(12), jumps)
    }

    @Test
    fun `clear cancels pending timeout and resets digits`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        var jumped: Int? = null
        c.onJump = { jumped = it }

        c.onDigit(9)
        c.clear()
        advanceTimeBy(5_000)
        runCurrent()

        assertNull(jumped)
        assertEquals("", c.digits.value)
    }

    @Test
    fun `timeout commit fires only once`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        val jumps = mutableListOf<Int>()
        c.onJump = { jumps += it }

        c.onDigit(5)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(5), jumps)

        // 已清空，不会再次触发
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(5), jumps)
    }

    @Test
    fun `hasDigits reflects input state`() = runTest {
        val c = ChannelNumberController(backgroundScope)
        assertEquals(false, c.hasDigits())
        c.onDigit(3)
        assertEquals(true, c.hasDigits())
        c.confirm()
        assertEquals(false, c.hasDigits())
    }
}
