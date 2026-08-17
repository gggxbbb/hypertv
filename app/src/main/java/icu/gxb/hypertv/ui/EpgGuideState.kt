package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.gxb.hypertv.data.entity.EpgProgramEntity

/**
 * 节目表（EPG Guide）全屏页的 UI 状态（ticket 10）。
 *
 * - 打开时时间窗口以当前小时为中心 ±3h，焦点在第一个频道行，只加载前
 *   [GUIDE_PAGE_SIZE] 个频道（避免 5000 频道一次性全量查询/绘制）
 * - 上下键移动行焦点；到已加载底部且还有更多频道时翻页追加一页
 * - 左右键移动时间窗口（±1 小时）
 * - 节目数据由调用方按 (loadedChannelCount, windowStartMs) 查询后经 [setPrograms]
 *   注入，状态机不直接访问数据层
 */
class EpgGuideState {

    var isOpen by mutableStateOf(false)
        private set

    /** 焦点行下标（对完整频道列表的索引，始终 < [loadedChannelCount]） */
    var focusedRow by mutableStateOf(0)
        private set

    /** 时间窗口起始（毫秒），窗口长度为 [WINDOW_DURATION_MS] */
    var windowStartMs by mutableStateOf(0L)
        private set

    /** 当前已加载的频道行数（前 N 个频道，翻页追加） */
    var loadedChannelCount by mutableStateOf(0)
        private set

    /** 已加载频道的时间轴节目，按 channelEpgId 分组（调用方注入） */
    var programsByChannel by mutableStateOf<Map<String, List<EpgProgramEntity>>>(emptyMap())
        private set

    /** 打开节目表：窗口默认当前小时 ±3h，焦点首行，只加载前 [GUIDE_PAGE_SIZE] 个频道 */
    fun open(nowMs: Long, totalChannels: Int) {
        isOpen = true
        focusedRow = 0
        windowStartMs = guideWindowStartFor(nowMs)
        loadedChannelCount = totalChannels.coerceAtMost(GUIDE_PAGE_SIZE)
    }

    /** 返回键关闭（回到播放页） */
    fun close() {
        isOpen = false
        programsByChannel = emptyMap()
    }

    /** 左右键移动时间窗口（±1 小时） */
    fun moveWindow(deltaHours: Int) {
        windowStartMs = moveGuideWindow(windowStartMs, deltaHours)
    }

    /**
     * 上下键移动行焦点。
     *
     * - 在已加载行内移动，两端钳制（顶部不越界，底部不越过已加载范围）
     * - 在底部继续向下且还有更多频道时翻页追加一页，焦点停留在原行，返回 true
     *   （调用方据此触发节目重新加载）
     *
     * @return 是否追加了频道页（需要重新加载节目数据）
     */
    fun moveFocus(delta: Int, totalChannels: Int): Boolean {
        if (totalChannels == 0 || loadedChannelCount == 0) return false
        val target = (focusedRow + delta).coerceIn(0, loadedChannelCount - 1)
        if (target != focusedRow) {
            focusedRow = target
            return false
        }
        // 已在边界：底部向下且还有更多频道 → 翻页追加
        if (delta > 0 && loadedChannelCount < totalChannels) {
            val added = minOf(GUIDE_PAGE_SIZE, totalChannels - loadedChannelCount)
            if (added > 0) {
                loadedChannelCount += added
                return true
            }
        }
        return false
    }

    /** 注入已加载频道的节目数据（调用方在 isOpen/window/loaded 变化时查询） */
    fun setPrograms(programs: Map<String, List<EpgProgramEntity>>) {
        programsByChannel = programs
    }
}
