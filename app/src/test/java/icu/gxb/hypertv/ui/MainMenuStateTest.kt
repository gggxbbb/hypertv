package icu.gxb.hypertv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主菜单单测（ticket 11 收尾）：
 * - 菜单项定义符合 spec 7.2 顺序：节目表 / 收藏列表 / 回放（v2 占位）/ 关于，不含分组筛选
 * - 回放 disabled 且标题带"即将推出"；节目表/收藏列表/关于启用；MAIN_MENU_ENABLED_COUNT == 3
 * - MainMenuState 开合与焦点移动（启用项内回绕、无启用项忽略）
 */
class MainMenuStateTest {

    // ---- 主菜单项定义 ----

    @Test
    fun `menu items follow spec 7_2 order with replay placeholder`() {
        assertEquals(
            listOf("节目表", "⭐ 收藏列表", "回放（即将推出）", "关于"),
            MAIN_MENU_ITEMS.map { it.title },
        )
    }

    @Test
    fun `replay is disabled v2 placeholder with coming-soon title`() {
        val replay = MAIN_MENU_ITEMS[2]
        assertFalse(replay.enabled)
        assertTrue(replay.title.contains("即将推出"))
    }

    @Test
    fun `guide favorites and about are enabled`() {
        assertTrue(MAIN_MENU_ITEMS[0].enabled) // 节目表
        assertTrue(MAIN_MENU_ITEMS[1].enabled) // 收藏列表
        assertTrue(MAIN_MENU_ITEMS[3].enabled) // 关于
        assertEquals(3, MAIN_MENU_ENABLED_COUNT)
    }

    // ---- MainMenuState 行为 ----

    @Test
    fun `open resets focus to first enabled item`() {
        val menu = MainMenuState()
        menu.moveFocus(1, MAIN_MENU_ENABLED_COUNT)

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

        menu.moveFocus(1, MAIN_MENU_ENABLED_COUNT)
        assertEquals(1, menu.selectedIndex)
        menu.moveFocus(1, MAIN_MENU_ENABLED_COUNT)
        assertEquals(2, menu.selectedIndex)
        // 到末尾再向下：回绕到第一个启用项
        menu.moveFocus(1, MAIN_MENU_ENABLED_COUNT)
        assertEquals(0, menu.selectedIndex)
        // 向上回绕到最后一个启用项
        menu.moveFocus(-1, MAIN_MENU_ENABLED_COUNT)
        assertEquals(2, menu.selectedIndex)
    }

    @Test
    fun `move focus ignored when no enabled items`() {
        val menu = MainMenuState()

        menu.moveFocus(1, 0)

        assertEquals(0, menu.selectedIndex)
    }
}
