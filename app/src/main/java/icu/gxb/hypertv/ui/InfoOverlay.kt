package icu.gxb.hypertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.data.entity.EpgProgramEntity

/**
 * Info 节目信息浮层（ticket 10）：播放页按 Info 键在屏幕上方显示当前频道的
 * 正在播放节目——节目名、起止时间（本地时区格式化）、节目简介（如有）。
 * 无 EPG 匹配/非播出时段显示"无节目信息"。
 *
 * 开合与数据注入由 [InfoOverlayState] + PlayerScreen 驱动，本组件只渲染。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun InfoOverlay(
    channelName: String?,
    program: EpgProgramEntity?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(max = INFO_MAX_WIDTH_DP)
            .clip(RoundedCornerShape(INFO_CORNER_RADIUS_DP))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = INFO_BG_ALPHA))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                text = channelName.orEmpty(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program == null) {
                Text(
                    text = "无节目信息",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = program.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "${formatTime(program.startTime)} - ${formatTime(program.endTime)}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!program.description.isNullOrBlank()) {
                    Text(
                        text = program.description,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = INFO_DESC_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

private const val INFO_BG_ALPHA = 0.85f
private const val INFO_DESC_MAX_LINES = 3
private val INFO_MAX_WIDTH_DP = 720.dp
private val INFO_CORNER_RADIUS_DP = 8.dp
