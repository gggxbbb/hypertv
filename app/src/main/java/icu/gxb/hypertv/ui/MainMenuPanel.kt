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
 * 主菜单项：标题 + 可用性。节目表/回放/关于为禁用占位（ticket 11 收尾），
 * 标题可见但不可聚焦。
 */
internal data class MenuItem(
    val title: String,
    val enabled: Boolean,
)

/** 主菜单项定义（v1 仅"收藏列表"可用） */
internal val MAIN_MENU_ITEMS = listOf(
    MenuItem(title = "⭐ 收藏列表", enabled = true),
    MenuItem(title = "节目表", enabled = false),
    MenuItem(title = "回放", enabled = false),
    MenuItem(title = "关于", enabled = false),
)

/** 可用项数量（焦点仅在启用项之间移动） */
internal val MAIN_MENU_ENABLED_COUNT = MAIN_MENU_ITEMS.count { it.enabled }

/**
 * 主菜单面板（ticket 06）：播放页上的半透明垂直列表，左侧定位。
 * [selectedIndex] 为焦点在启用项序列中的下标；禁用项渲染为降透明文本。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MainMenuPanel(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = MENU_BG_ALPHA))
            .padding(vertical = 16.dp),
    ) {
        var enabledPos = 0
        MAIN_MENU_ITEMS.forEach { item ->
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
