package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.gxb.hypertv.player.Channel

/**
 * 收藏列表全屏页的 UI 状态（ticket 06）：开合与列表焦点频道。
 * 行为与频道列表浮层 [ChannelListOverlayState] 一致：上下键移动焦点（两端钳制），
 * 列表内容由调用方把最新的收藏列表传入。
 */
class FavoritesScreenState {

    var isOpen by mutableStateOf(false)
        private set

    /** 当前焦点频道的 id；null 表示列表为空或尚未确定 */
    var focusedChannelId by mutableStateOf<String?>(null)
        private set

    /** 进入收藏列表页：焦点交由调用方按当前播放频道/列表定位 */
    fun open() {
        isOpen = true
        focusedChannelId = null
    }

    /** 返回键/播放选中频道后关闭（回到播放页） */
    fun close() {
        isOpen = false
    }

    /** 上下键移动焦点（在当前列表内，两端钳制）；列表为空则忽略 */
    fun moveFocus(delta: Int, favorites: List<Channel>) {
        if (favorites.isEmpty()) return
        val current = favorites.indexOfFirst { it.id == focusedChannelId }
        val target = when {
            current < 0 -> if (delta > 0) 0 else favorites.lastIndex
            else -> (current + delta).coerceIn(0, favorites.lastIndex)
        }
        focusedChannelId = favorites[target].id
    }

    /** 焦点定位（由页面开启/收藏列表变化后的自动定位使用） */
    fun setFocus(channelId: String?) {
        focusedChannelId = channelId
    }
}
