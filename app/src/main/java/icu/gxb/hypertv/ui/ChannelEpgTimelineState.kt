package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.player.Channel
import java.time.ZoneId

/**
 * 频道列表浮层右栏 EPG 时间轴状态（浮层重构）。
 *
 * 数据加载经 [programLoader] 注入（生产为 Room 一次性查询），便于 JVM 单测注入 fake：
 * - 选中频道变化时调用 [loadFor]：以当前小时为中心 ±[WINDOW_CENTER_HOURS] 为窗口查询节目
 *   （复用 [guideWindowStartFor] 的窗口计算）
 * - 未匹配 EPG（epgId == null）或频道为空 → 清空节目与加载态
 * - 竞态防护：焦点频道快速切换时，过期查询结果不覆盖新频道数据
 */
class ChannelEpgTimelineState(
    private val programLoader: suspend (channelEpgId: String, startMs: Long, endMs: Long) -> List<EpgProgramEntity>,
) {

    /** 时间窗口起始（毫秒），窗口长度为 [WINDOW_DURATION_MS] */
    var windowStartMs by mutableStateOf(0L)
        private set

    /** 选中频道在窗口内的节目，按 startTime 升序 */
    var programs by mutableStateOf<List<EpgProgramEntity>>(emptyList())
        private set

    /** 查询进行中（右栏显示加载态） */
    var isLoading by mutableStateOf(false)
        private set

    /** 查询序号：频道切换时自增，旧查询结果据此失效 */
    private var loadSeq = 0

    /**
     * 为选中频道加载 EPG 时间轴数据。频道未匹配 EPG（epgId == null）或为 null 时清空状态。
     * 窗口以 [nowMs] 所在小时为中心 ±[WINDOW_CENTER_HOURS] 小时。
     */
    suspend fun loadFor(channel: Channel?, nowMs: Long, zone: ZoneId = EPG_ZONE) {
        val seq = ++loadSeq
        windowStartMs = guideWindowStartFor(nowMs, zone)
        val epgId = channel?.epgId
        if (epgId == null) {
            programs = emptyList()
            isLoading = false
            return
        }
        isLoading = true
        val result = try {
            programLoader(epgId, windowStartMs, windowStartMs + WINDOW_DURATION_MS)
        } catch (e: Exception) {
            emptyList()
        }
        if (seq != loadSeq) return // 期间焦点频道已切换，丢弃过期结果
        programs = result.sortedBy { it.startTime }
        isLoading = false
    }

    /** 清空状态（浮层收起时调用），并令在途查询失效 */
    fun reset() {
        loadSeq++
        programs = emptyList()
        isLoading = false
    }
}
