package icu.gxb.hypertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.player.Channel

/**
 * 收藏列表全屏页（ticket 06）：主菜单进入，展示全部收藏频道（isFavorite=true，
 * 按 orderIndex 排序）。上下键移动焦点、OK 播放、返回键回到播放页、左右键无功能
 * （由 PlayerScreen 的按键分发保证，本页只渲染）。
 *
 * 空状态：无收藏时居中显示引导文案"暂无收藏，按星号键收藏频道"。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun FavoritesScreen(
    favorites: List<Channel>,
    focusedChannelId: String?,
    currentChannelId: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = "⭐ 收藏列表",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无收藏，按星号键收藏频道",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(favorites, key = { _, ch -> ch.id }) { _, channel ->
                    ChannelRow(
                        channel = channel,
                        channelNumber = channel.orderIndex + 1,
                        isFocused = channel.id == focusedChannelId,
                        isCurrent = channel.id == currentChannelId,
                    )
                }
            }
        }
    }
}
