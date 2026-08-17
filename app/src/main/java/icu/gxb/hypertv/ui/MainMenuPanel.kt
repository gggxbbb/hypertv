package icu.gxb.hypertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 主菜单项：标题 + 可用性。回放为禁用占位（v2 即将推出，标题可见但不可聚焦）。
 */
internal data class MenuItem(
    val title: String,
    val enabled: Boolean,
)

/**
 * 主菜单项定义（ticket 01 收藏切换）：接收当前播放频道收藏状态生成动态菜单。
 *
 * 顺序：节目表 / 收藏本频道（动态文案，第 2 项）/ 收藏列表 / 回放（v2 占位）/ 关于，
 * 不含分组筛选入口。收藏项文案随收藏状态切换：未收藏「★ 收藏本频道」、
 * 已收藏「☆ 取消收藏本频道」，便于无星号/红键的遥控器经菜单 OK 切换收藏。
 */
internal fun mainMenuItems(isFavorite: Boolean): List<MenuItem> = listOf(
    MenuItem(title = "节目表", enabled = true),
    MenuItem(
        title = if (isFavorite) "☆ 取消收藏本频道" else "★ 收藏本频道",
        enabled = true,
    ),
    MenuItem(title = "⭐ 收藏列表", enabled = true),
    MenuItem(title = "回放（即将推出）", enabled = false),
    MenuItem(title = "关于", enabled = true),
)

/** 可用项数量（焦点仅在启用项之间移动） */
internal fun mainMenuEnabledCount(items: List<MenuItem>): Int = items.count { it.enabled }

/**
 * 主菜单面板（ticket 06）：播放页上的半透明垂直列表，左侧定位。
 * [selectedIndex] 为焦点在启用项序列中的下标；禁用项渲染为降透明文本。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MainMenuPanel(
    items: List<MenuItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = MENU_BG_ALPHA))
            .padding(vertical = 16.dp),
    ) {
        var enabledPos = 0
        items.forEach { item ->
            val focused = item.enabled && enabledPos == selectedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focused) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = item.title,
                    color = when {
                        focused -> MaterialTheme.colorScheme.onPrimary
                        item.enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_TEXT_ALPHA)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (item.enabled) enabledPos++
        }
    }
}

private const val MENU_BG_ALPHA = 0.75f
private const val DISABLED_TEXT_ALPHA = 0.35f
