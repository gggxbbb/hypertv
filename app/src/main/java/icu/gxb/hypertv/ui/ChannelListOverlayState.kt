package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.gxb.hypertv.player.Channel

/**
 * 频道列表浮层的 UI 状态（ticket 05）：开合、选中分组标签、列表焦点频道。
 *
 * 过滤逻辑见 [ChannelListFilter]（纯函数）；焦点移动与标签切换在此维护，
 * 列表内容由调用方（PlayerScreen）把过滤后的当前列表传入。
 */
class ChannelListOverlayState {

    var isOpen by mutableStateOf(false)
        private set

    var selectedTab by mutableStateOf(ChannelListFilter.TAB_ALL)
        private set

    /** 当前焦点频道的 id；null 表示列表为空或尚未确定 */
    var focusedChannelId by mutableStateOf<String?>(null)
        private set

    /** OK 呼出：默认"全部"标签，焦点交由调用方按当前播放频道定位 */
    fun open() {
        isOpen = true
        selectedTab = ChannelListFilter.TAB_ALL
        focusedChannelId = null
    }

    /** 返回键收起（不换台） */
    fun close() {
        isOpen = false
    }

    /** 切换标签后焦点置空，由调用方按新过滤列表重新定位到当前播放频道 */
    fun setTab(tab: String) {
        selectedTab = tab
        focusedChannelId = null
    }

    /** 左右键切换标签（回绕）；tabs 为空则忽略 */
    fun switchTab(delta: Int, tabs: List<String>) {
        if (tabs.isEmpty()) return
        val current = tabs.indexOf(selectedTab).let { if (it >= 0) it else 0 }
        setTab(tabs[(current + delta).mod(tabs.size)])
    }

    /** 上下键移动焦点（在当前过滤列表内，两端钳制）；列表为空则忽略 */
    fun moveFocus(delta: Int, channels: List<Channel>) {
        if (channels.isEmpty()) return
        val current = channels.indexOfFirst { it.id == focusedChannelId }
        val target = when {
            current < 0 -> if (delta > 0) 0 else channels.lastIndex
            else -> (current + delta).coerceIn(0, channels.lastIndex)
        }
        focusedChannelId = channels[target].id
    }

    /** 焦点定位（由浮层开启/标签切换后的自动定位使用） */
    fun setFocus(channelId: String?) {
        focusedChannelId = channelId
    }
}
