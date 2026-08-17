package icu.gxb.hypertv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主菜单单测（ticket 01 收藏切换收尾）：
 * - 菜单项定义顺序：节目表 / 收藏本频道（动态文案，第 2 项）/ 收藏列表 / 回放（v2 占位）/ 关于
 * - 收藏项文案随当前频道收藏状态切换：未收藏「★ 收藏本频道」、已收藏「☆ 取消收藏本频道」
 * - 回放 disabled 且标题带"即将推出"；其余 4 项启用；enabledCount == 4
 * - MainMenuState 开合与焦点移动（启用项内回绕、无启用项忽略）
 */
class MainMenuStateTest {

    // ---- 主菜单项定义 ----

    @Test
    fun `menu items follow order with favorite toggle as second item`() {
        assertEquals(
            listOf("节目表", "★ 收藏本频道", "⭐ 收藏列表", "回放（即将推出）", "关于"),
            mainMenuItems(isFavorite = false).map { it.title },
        )
    }

    @Test
    fun `favorite toggle title switches with favorite state`() {
        assertEquals("★ 收藏本频道", mainMenuItems(isFavorite = false)[1].title)
        assertEquals("☆ 取消收藏本频道", mainMenuItems(isFavorite = true)[1].title)
    }

    @Test
    fun `favorite toggle item always enabled`() {
        assertTrue(mainMenuItems(isFavorite = false)[1].enabled)
        assertTrue(mainMenuItems(isFavorite = true)[1].enabled)
    }

    @Test
    fun `replay is disabled v2 placeholder with coming-soon title`() {
        val replay = mainMenuItems(isFavorite = false)[3]
        assertFalse(replay.enabled)
        assertTrue(replay.title.contains("即将推出"))
    }

    @Test
    fun `guide favorite favorites and about are enabled`() {
        val items = mainMenuItems(isFavorite = false)
        assertTrue(items[0].enabled) // 节目表
        assertTrue(items[1].enabled) // 收藏本频道
        assertTrue(items[2].enabled) // 收藏列表
        assertTrue(items[4].enabled) // 关于
        assertEquals(4, mainMenuEnabledCount(items))
    }

    // ---- MainMenuState 行为 ----

    @Test
    fun `open resets focus to first enabled item`() {
        val menu = MainMenuState()
        menu.moveFocus(1, 4)

        menu.open()

        assertTrue(menu.isOpen)
        assertEquals(0, menu.selectedIndex)
    }

    @Test
    fun `close hides menu`() {
        val menu = MainMenuState().apply { open() }

        menu.close()

        assertFalse(menu.isOpen)
    }

    @Test
    fun `move focus wraps within enabled items`() {
        val menu = MainMenuState().apply { open() }

        menu.moveFocus(1, 4)
        assertEquals(1, menu.selectedIndex)
        menu.moveFocus(1, 4)
        assertEquals(2, menu.selectedIndex)
        menu.moveFocus(1, 4)
        assertEquals(3, menu.selectedIndex)
        // 到末尾再向下：回绕到第一个启用项
        menu.moveFocus(1, 4)
        assertEquals(0, menu.selectedIndex)
        // 向上回绕到最后一个启用项
        menu.moveFocus(-1, 4)
        assertEquals(3, menu.selectedIndex)
    }

    @Test
    fun `move focus ignored when no enabled items`() {
        val menu = MainMenuState()

        menu.moveFocus(1, 0)

        assertEquals(0, menu.selectedIndex)
    }
}
