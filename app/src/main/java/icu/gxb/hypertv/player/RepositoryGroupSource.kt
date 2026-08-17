package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.repository.HypertvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 从 Room 分组表构造分组名流（按 orderIndex 升序）。发射到主线程，与频道流
 * [RepositoryChannelSource] 一致，供 Controller 在其 Main.immediate 上下文中同步消费。
 */
class RepositoryGroupSource(
    private val repository: HypertvRepository,
) : GroupSource {

    override val groups: Flow<List<String>> = repository.groups
        .map { list -> list.map { it.name } }
        .flowOn(Dispatchers.Main.immediate)
}
