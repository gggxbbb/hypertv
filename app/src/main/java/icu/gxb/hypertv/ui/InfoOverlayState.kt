package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.gxb.hypertv.data.entity.EpgProgramEntity

/** Info 浮层查询窗口半宽：查 [now-δ, now+δ] 内节目，再取正在播放的 */
const val INFO_QUERY_DELTA_MS = 2 * HOUR_MS

/** Info 浮层自动收起超时（ticket 10：3-5 秒） */
const val INFO_AUTO_CLOSE_MS = 4_000L

/**
 * Info 节目信息浮层的 UI 状态（ticket 10）。
 *
 * - Info 键开合；再次按 Info 或 [INFO_AUTO_CLOSE_MS] 超时自动收起
 * - 当前节目由调用方在浮层打开/频道切换时查询并 [setProgram]；查询窗口
 *   [now - INFO_QUERY_DELTA_MS, now + INFO_QUERY_DELTA_MS]，由 [findCurrentProgram]
 *   取其中正在播放的节目（无则显示"无节目信息"）
 * - 换台（上下键）时浮层保持打开并刷新为新频道节目，超时窗口重新计时
 */
class InfoOverlayState {

    var isOpen by mutableStateOf(false)
        private set

    /** 当前频道正在播放的节目；null 表示无 EPG 匹配或不在播出时段 */
    var program by mutableStateOf<EpgProgramEntity?>(null)
        private set

    fun open() {
        isOpen = true
        program = null
    }

    fun close() {
        isOpen = false
        program = null
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    /** 注入查询结果（调用方用 [findCurrentProgram] 从窗口内节目里选取） */
    fun updateProgram(program: EpgProgramEntity?) {
        this.program = program
    }

    /** 超时收起：与 close 同一语义，作为状态机对外显式入口 */
    fun onTimeout() = close()
}
