package icu.gxb.hypertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.player.Channel

/**
 * 节目表（EPG Guide）全屏页（ticket 10）：主菜单"节目表"进入，与收藏列表页同级
 * 的全屏叠加层。横向时间轴网格——左侧固定宽度频道列，右侧节目条按 start/end 比例
 * 定位到时间轴上。
 *
 * - 单 LazyColumn 承载网格行（行内 = 左侧频道单元 + 右侧节目条），垂直滚动天然同步；
 *   时间轴刻度为固定头部，不随行滚动
 * - 上下键行焦点、左右键移动时间窗口（±1 小时）、OK 播放焦点频道、返回键退出
 *   （按键语义由 PlayerScreen 分发，本页只渲染）
 * - 只渲染已加载的频道行（[EpgGuideState.loadedChannelCount]，翻页追加），节目条
 *   用 Modifier.offset/width 按比例定位，行内 Box 裁剪，避免一次性绘制 5000×N 节目条
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpgGuideScreen(
    channels: List<Channel>,
    currentChannelId: String?,
    guide: EpgGuideState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // 焦点行滚动到可见
    LaunchedEffect(guide.focusedRow) {
        if (guide.isOpen && guide.focusedRow > 0) listState.animateScrollToItem(guide.focusedRow)
    }

    val nowMs = remember { System.currentTimeMillis() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(vertical = 16.dp),
    ) {
        // 头部：标题 + 当前时间窗口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "节目表",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = formatWindowRange(guide.windowStartMs, WINDOW_DURATION_MS),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val gridWidthPx = with(density) { (maxWidth - CHANNEL_COLUMN_WIDTH).toPx() }
            val gridLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = GRID_LINE_ALPHA)
            val currentTimeX = remember(guide.windowStartMs, nowMs) {
                layoutProgram(
                    windowStartMs = guide.windowStartMs,
                    windowEndMs = guide.windowStartMs + WINDOW_DURATION_MS,
                    programStart = nowMs,
                    programEnd = nowMs + 1,
                    gridWidthPx = gridWidthPx,
                )?.let { if (it.x < gridWidthPx) it.x else null }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // 时间轴头部：左侧频道列占位 + 右侧小时刻度
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TIMELINE_HEIGHT)
                        .drawBehind {
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1f,
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .width(CHANNEL_COLUMN_WIDTH)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "频道",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    TimelineTicks(
                        windowStartMs = guide.windowStartMs,
                        gridWidthPx = gridWidthPx,
                        gridLineColor = gridLineColor,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                // 网格：单 LazyColumn，行内 = 频道单元 + 节目条
                val visibleChannels = channels.take(guide.loadedChannelCount)
                if (visibleChannels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无频道",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(visibleChannels, key = { it.id }) { channel ->
                            GuideRow(
                                channel = channel,
                                channelNumber = channel.orderIndex + 1,
                                isFocused = channel.id == channels.getOrNull(guide.focusedRow)?.id,
                                isCurrent = channel.id == currentChannelId,
                                programs = guide.programsByChannel[channel.epgId].orEmpty(),
                                windowStartMs = guide.windowStartMs,
                                gridWidthPx = gridWidthPx,
                                gridLineColor = gridLineColor,
                                currentTimeX = currentTimeX,
                            )
                        }
                    }
                }
            }
        }

        // 底部操作提示
        Text(
            text = "上下键切换频道 · 左右键滚动时间 · OK 播放 · 返回退出",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/** 时间轴头部：每 1 小时一格，格宽 = gridWidthPx / 6，显示整点刻度。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimelineTicks(
    windowStartMs: Long,
    gridWidthPx: Float,
    gridLineColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        for (i in 0 until WINDOW_DURATION_HOURS) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        if (i > 0) {
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 1f,
                            )
                        }
                    },
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = formatHourTick(windowStartMs + i * HOUR_MS),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** 网格行：左侧频道单元 + 右侧节目条。焦点高亮整行。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideRow(
    channel: Channel,
    channelNumber: Int,
    isFocused: Boolean,
    isCurrent: Boolean,
    programs: List<EpgProgramEntity>,
    windowStartMs: Long,
    gridWidthPx: Float,
    gridLineColor: Color,
    currentTimeX: Float?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT)
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = FOCUS_BG_ALPHA)
                    isCurrent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CURRENT_BG_ALPHA)
                    else -> Color.Transparent
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧频道单元（固定宽度）
        GuideChannelCell(
            channel = channel,
            channelNumber = channelNumber,
            isCurrent = isCurrent,
            modifier = Modifier.width(CHANNEL_COLUMN_WIDTH).fillMaxHeight(),
        )
        // 右侧节目条区域
        GuideProgramRow(
            programs = programs,
            windowStartMs = windowStartMs,
            gridWidthPx = gridWidthPx,
            gridLineColor = gridLineColor,
            currentTimeX = currentTimeX,
            isFocused = isFocused,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** 左侧频道单元：频道号 + 台标缩略图 + 频道名（160dp 内简化排版）。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideChannelCell(
    channel: Channel,
    channelNumber: Int,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clipToBounds()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channelNumber.toString().padStart(3, ' '),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = NUMBER_TEXT_ALPHA),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(34.dp),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * 右侧节目条区域：节目按 start/end 映射到水平位置与宽度（offset/width 比例定位），
 * 行内 Box 裁剪避免越界。无节目（未匹配 EPG）时显示浅色占位。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideProgramRow(
    programs: List<EpgProgramEntity>,
    windowStartMs: Long,
    gridWidthPx: Float,
    gridLineColor: Color,
    currentTimeX: Float?,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                // 小时分界竖线 + 当前时间线
                val hourWidth = size.width / WINDOW_DURATION_HOURS
                for (i in 1 until WINDOW_DURATION_HOURS) {
                    drawLine(
                        color = gridLineColor,
                        start = Offset(i * hourWidth, 0f),
                        end = Offset(i * hourWidth, size.height),
                        strokeWidth = 1f,
                    )
                }
                if (currentTimeX != null && currentTimeX in 0f..size.width) {
                    drawLine(
                        color = CURRENT_TIME_COLOR,
                        start = Offset(currentTimeX, 0f),
                        end = Offset(currentTimeX, size.height),
                        strokeWidth = 2f,
                    )
                }
            },
    ) {
        if (programs.isEmpty()) {
            Text(
                text = "无节目信息",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            )
        } else {
            programs.forEach { prog ->
                val layout = layoutProgram(
                    windowStartMs = windowStartMs,
                    windowEndMs = windowStartMs + WINDOW_DURATION_MS,
                    programStart = prog.startTime,
                    programEnd = prog.endTime,
                    gridWidthPx = gridWidthPx,
                ) ?: return@forEach
                Box(
                    modifier = Modifier
                        .offset(x = with(density) { layout.x.toDp() })
                        .width(with(density) { layout.width.toDp() })
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isFocused) PROGRAM_BAR_FOCUS_COLOR else PROGRAM_BAR_COLOR,
                        ),
                ) {
                    Text(
                        text = prog.title,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

private const val CHANNEL_COLUMN_WIDTH_DP = 160
private val CHANNEL_COLUMN_WIDTH = CHANNEL_COLUMN_WIDTH_DP.dp
private val GUIDE_ROW_HEIGHT = 56.dp
private val TIMELINE_HEIGHT = 34.dp
private const val FOCUS_BG_ALPHA = 0.35f
private const val CURRENT_BG_ALPHA = 0.18f
private const val GRID_LINE_ALPHA = 0.14f
private const val NUMBER_TEXT_ALPHA = 0.6f

private val PROGRAM_BAR_COLOR = Color(0xFF3D6FA3)
private val PROGRAM_BAR_FOCUS_COLOR = Color(0xFF5A8EC2)
private val CURRENT_TIME_COLOR = Color(0xFFFFD740)
private val LOGO_PLACEHOLDER_COLOR = Color(0xFF3A3A3A)
