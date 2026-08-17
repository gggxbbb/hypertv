package icu.gxb.hypertv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.player.Channel

/**
 * 频道列表浮层右栏（浮层重构）：选中频道信息 + EPG 时间轴。
 *
 * - 顶部：频道名（含收藏 ★ 标记）+ EPG 状态（已匹配显示当前节目概要；未匹配显示灰字）
 * - 下方：横向时间轴（小时刻度 [TimelineTicks] + 单频道节目条 [GuideProgramRow]，
 *   全部复用 [GuideTimeline] 纯函数与 Guide 页共享绘制组件），当前时间竖线
 * - 选中频道变化由调用方驱动 [ChannelEpgTimelineState.loadFor] 刷新数据
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChannelEpgTimeline(
    channel: Channel?,
    epgTimeline: ChannelEpgTimelineState,
    modifier: Modifier = Modifier,
) {
    val nowMs = remember { System.currentTimeMillis() }
    val status = epgStatusText(channel, epgTimeline, nowMs)

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部：频道名 + EPG 状态
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        if (channel?.isFavorite == true) append("★ ")
                        append(channel?.name.orEmpty())
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (status == "未匹配 EPG") 0.5f else 0.75f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 时间轴：小时刻度 + 单频道节目条（复用 Guide 共享绘制组件）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 20.dp, top = 12.dp, end = 20.dp),
        ) {
            val density = LocalDensity.current
            val gridWidthPx = with(density) { maxWidth.toPx() }
            val gridLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = GRID_LINE_ALPHA)
            val currentTimeX = remember(epgTimeline.windowStartMs, nowMs) {
                layoutProgram(
                    windowStartMs = epgTimeline.windowStartMs,
                    windowEndMs = epgTimeline.windowStartMs + WINDOW_DURATION_MS,
                    programStart = nowMs,
                    programEnd = nowMs + 1,
                    gridWidthPx = gridWidthPx,
                )?.let { if (it.x < gridWidthPx) it.x else null }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                TimelineTicks(
                    windowStartMs = epgTimeline.windowStartMs,
                    gridWidthPx = gridWidthPx,
                    gridLineColor = gridLineColor,
                    modifier = Modifier.fillMaxWidth().height(TICK_HEIGHT),
                )
                GuideProgramRow(
                    programs = epgTimeline.programs,
                    windowStartMs = epgTimeline.windowStartMs,
                    gridWidthPx = gridWidthPx,
                    gridLineColor = gridLineColor,
                    currentTimeX = currentTimeX,
                    isFocused = false,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/** 右栏状态文本：未匹配 EPG → 灰字；已匹配/加载中 → 当前节目概要或空态。 */
private fun epgStatusText(
    channel: Channel?,
    epgTimeline: ChannelEpgTimelineState,
    nowMs: Long,
): String = when {
    channel == null -> "暂无频道"
    channel.epgId == null -> "未匹配 EPG"
    epgTimeline.isLoading -> "节目加载中…"
    epgTimeline.programs.isEmpty() -> "无节目信息"
    else -> {
        val current = findCurrentProgram(epgTimeline.programs, nowMs)
        if (current != null) {
            "现在播出：${current.title}（${formatTime(current.startTime)} - ${formatTime(current.endTime)}）"
        } else {
            "无节目信息"
        }
    }
}

private val TICK_HEIGHT = 24.dp
private const val GRID_LINE_ALPHA = 0.14f
