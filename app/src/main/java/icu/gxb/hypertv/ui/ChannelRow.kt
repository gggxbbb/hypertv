package icu.gxb.hypertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import icu.gxb.hypertv.player.Channel

/**
 * 频道行：频道号（orderIndex+1，固定宽度样式）+ 台标（统一占位灰块 + Coil 异步加载）+ 频道名。
 * 频道列表浮层与收藏列表页共用（ticket 06）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChannelRow(
    channel: Channel,
    channelNumber: Int,
    isFocused: Boolean,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = FOCUS_BG_ALPHA)
                    isCurrent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CURRENT_BG_ALPHA)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channelNumber.toString().padStart(3, ' '),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = NUMBER_TEXT_ALPHA),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp),
        )
        // 台标：统一灰色块占位 + Coil 异步加载（磁盘缓存默认开启）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LOGO_PLACEHOLDER_COLOR),
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = channel.name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private const val FOCUS_BG_ALPHA = 0.45f
private const val CURRENT_BG_ALPHA = 0.25f
private const val NUMBER_TEXT_ALPHA = 0.6f

/** 台标统一占位灰块颜色 */
private val LOGO_PLACEHOLDER_COLOR = Color(0xFF3A3A3A)
