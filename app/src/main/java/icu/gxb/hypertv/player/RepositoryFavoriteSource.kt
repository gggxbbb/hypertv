package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.repository.HypertvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 从 Room 收藏频道表构造收藏流：排除隐藏频道（与浮层"收藏"标签的可见频道语义一致），
 * 顺序沿用 Repository 的 orderIndex 升序；写操作直接落到 Repository（Room Flow
 * 天然把电视端/WebUI 的改动广播给所有订阅方）。
 *
 * 频道号按收藏列表位置动态生成（number = index + 1）。
 */
class RepositoryFavoriteSource(
    private val repository: HypertvRepository,
) : FavoriteDataSource {

    override val favoriteChannels: Flow<List<Channel>> = repository.favoriteChannels
        .map { list -> list.filterNot { it.isHidden } }
        .map { list -> list.mapIndexed { index, entity -> entity.toChannel(index + 1) } }
        .flowOn(Dispatchers.Main.immediate)

    override suspend fun setFavorite(channelId: String, isFavorite: Boolean) =
        repository.setChannelFavorite(channelId, isFavorite)
}
