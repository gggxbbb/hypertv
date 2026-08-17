package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.repository.HypertvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 从 Room 的频道表构造可见频道流：过滤 isHidden=false（隐藏频道不参与播放/换台），
 * 顺序沿用 Repository 的 orderIndex 升序。发射到主线程，供 Controller 在其
 * Main.immediate 上下文中同步消费。
 *
 * 频道号按排序后列表位置动态生成（number = index + 1，永远连续无空洞）。
 */
class RepositoryChannelSource(
    private val repository: HypertvRepository,
) : ChannelSource {

    override val visibleChannels: Flow<List<Channel>> = repository.channels
        .map { list -> list.filterNot { it.isHidden } }
        .map { list -> list.mapIndexed { index, entity -> entity.toChannel(index + 1) } }
        .flowOn(Dispatchers.Main.immediate)
}
