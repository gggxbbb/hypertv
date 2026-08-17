package icu.gxb.hypertv.player

import kotlinx.coroutines.flow.Flow

/**
 * 频道源抽象：提供可见频道列表（isHidden=false，按 orderIndex 排序）。
 * Controller 观察该 Flow 并缓存当前列表；数据变化（导入/删除源）时重新发射。
 */
interface ChannelSource {
    val visibleChannels: Flow<List<Channel>>
}
