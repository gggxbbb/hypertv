package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主菜单的 UI 状态（ticket 06）：开合与焦点索引。
 *
 * 菜单项含启用项与禁用占位项（回放 v2 占位，ticket 11 收尾）；焦点只在
 * 启用项之间移动（[focusableCount] 由调用方按菜单项定义传入），禁用项可见但不可聚焦。
 */
class MainMenuState {

    var isOpen by mutableStateOf(false)
        private set

    /** 焦点在启用项序列中的下标（0 = 第一个启用项"节目表"） */
    var selectedIndex by mutableStateOf(0)
        private set

    /** Menu 键呼出：焦点回落到第一个启用项 */
    fun open() {
        isOpen = true
        selectedIndex = 0
    }

    /** 返回键关闭（回到播放页） */
    fun close() {
        isOpen = false
    }

    /** 上下键移动焦点（在启用项内回绕）；无启用项则忽略 */
    fun moveFocus(delta: Int, focusableCount: Int) {
        if (focusableCount <= 0) return
        selectedIndex = (selectedIndex + delta).mod(focusableCount)
    }
}
