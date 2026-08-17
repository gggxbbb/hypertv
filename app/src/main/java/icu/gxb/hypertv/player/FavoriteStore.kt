package icu.gxb.hypertv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 收藏数据访问抽象（ticket 06）。生产实现走 Repository（Room Flow 实时同步到 WebUI），
 * 单测注入 fake 验证 toggle 逻辑与 Flow 反映。
 */
interface FavoriteDataSource {
    /** 收藏频道（按 orderIndex 升序，且排除隐藏频道，与浮层"收藏"标签语义一致） */
    val favoriteChannels: Flow<List<Channel>>

    /** 写收藏状态（Repository → Room 表，任何订阅方经 Flow 自动刷新） */
    suspend fun setFavorite(channelId: String, isFavorite: Boolean)
}

/**
 * 收藏状态机（ticket 06）：缓存收藏频道列表（StateFlow，供收藏列表页/播放页实时渲染），
 * 并提供一键 toggle。
 *
 * - 数据源为 Room Flow：WebUI 侧改动（ticket 07 起）与电视端改动都会经同一 Flow 驱动 UI 刷新
 * - [mirror] 是本地收藏 id 镜像，随每次 Flow 发射重建；toggle 时先同步更新镜像再写库，
 *   保证遥控器快速连按（含长按自动重复）下切换语义不依赖 Flow 发射的异步时机
 */
class FavoriteStore(
    private val dataSource: FavoriteDataSource,
    private val scope: CoroutineScope,
) {

    private val _favorites = MutableStateFlow<List<Channel>>(emptyList())
    val favorites: StateFlow<List<Channel>> = _favorites.asStateFlow()

    /** 本地收藏 id 镜像（单一真源，Flow 发射时整体重建） */
    private val mirror = mutableSetOf<String>()

    init {
        scope.launch {
            dataSource.favoriteChannels.collect { list ->
                _favorites.value = list
                mirror.clear()
                mirror.addAll(list.map { it.id })
            }
        }
    }

    fun isFavorite(channelId: String): Boolean = channelId in mirror

    /**
     * 切换收藏状态：写库并返回新状态（true=已收藏，false=已取消），供 UI 立即显示提示。
     * 返回值为切换后的目标值，与镜像同步，不依赖 Flow 发射时机。
     */
    suspend fun toggle(channelId: String): Boolean {
        val newValue = !isFavorite(channelId)
        if (newValue) mirror.add(channelId) else mirror.remove(channelId)
        dataSource.setFavorite(channelId, newValue)
        return newValue
    }
}
