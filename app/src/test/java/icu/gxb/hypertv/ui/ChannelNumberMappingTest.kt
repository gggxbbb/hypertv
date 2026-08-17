package icu.gxb.hypertv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 频道号（orderIndex+1，1-based）→ 列表索引映射单测（ticket 05）。
 * 覆盖：正向映射、超范围取模回绕、0/负数回绕到列表尾部、空列表无目标。
 */
class ChannelNumberMappingTest {

    @Test
    fun `channel number maps to zero based index`() {
        assertEquals(0, ChannelNumberMapping.toIndex(1, 10))
        assertEquals(4, ChannelNumberMapping.toIndex(5, 10))
        assertEquals(9, ChannelNumberMapping.toIndex(10, 10))
    }

    @Test
    fun `number beyond size wraps around`() {
        assertEquals(0, ChannelNumberMapping.toIndex(11, 10)) // 10→0 回绕后
        assertEquals(3, ChannelNumberMapping.toIndex(14, 10)) // 13 mod 10 = 3
        assertEquals(9, ChannelNumberMapping.toIndex(20, 10)) // 19 mod 10 = 9
    }

    @Test
    fun `number zero or negative wraps to end of list`() {
        assertEquals(9, ChannelNumberMapping.toIndex(0, 10))
        assertEquals(8, ChannelNumberMapping.toIndex(-1, 10))
    }

    @Test
    fun `single channel always maps to zero regardless of number`() {
        assertEquals(0, ChannelNumberMapping.toIndex(1, 1))
        assertEquals(0, ChannelNumberMapping.toIndex(5, 1))
    }

    @Test
    fun `empty channel list has no target`() {
        assertNull(ChannelNumberMapping.toIndex(1, 0))
        assertNull(ChannelNumberMapping.toIndex(1, -5))
    }
}
