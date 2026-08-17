package icu.gxb.hypertv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.data.entity.EpgProgramEntity

/**
 * EPG 时间轴共享绘制组件（浮层重构抽取）。
 *
 * 原为 [EpgGuideScreen] 私有实现，抽取为包内共享后同时被节目表全屏页与频道列表浮层
 * 右栏复用，避免复制粘贴大段绘制逻辑。布局计算一律复用 [GuideTimeline] 的纯函数
 * （[layoutProgram]、[formatHourTick] 等）。
 */

/**
 * 时间轴头部：每 1 小时一格，格宽 = gridWidthPx / 6，显示整点刻度。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TimelineTicks(
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

/**
 * 单频道节目条区域：节目按 start/end 映射到水平位置与宽度（offset/width 比例定位），
 * 区域 Box 裁剪避免越界。无节目（未匹配 EPG 或窗口内无数据）时显示浅色占位。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun GuideProgramRow(
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
                // 小时分界竖线（当前时间游标在覆盖层绘制，见下方 Canvas）
                val hourWidth = size.width / WINDOW_DURATION_HOURS
                for (i in 1 until WINDOW_DURATION_HOURS) {
                    drawLine(
                        color = gridLineColor,
                        start = Offset(i * hourWidth, 0f),
                        end = Offset(i * hourWidth, size.height),
                        strokeWidth = 1f,
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
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                    )
                }
            }
        }
        // 覆盖层：当前时间游标画在所有节目条之上（用户需求：游标覆盖在时间轴上方）
        if (currentTimeX != null) {
            Canvas(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (currentTimeX in 0f..size.width) {
                    drawLine(
                        color = CURRENT_TIME_COLOR,
                        start = Offset(currentTimeX, 0f),
                        end = Offset(currentTimeX, size.height),
                        strokeWidth = 2f,
                    )
                }
            }
        }
    }
}

/** 节目条底色（黑白灰配色：深灰表面，白字） */
internal val PROGRAM_BAR_COLOR = Color(0xFF3A3A42)

/** 焦点行节目条底色（略浅） */
internal val PROGRAM_BAR_FOCUS_COLOR = Color(0xFF5E5E68)

/** 当前时间竖线颜色（白色指示） */
internal val CURRENT_TIME_COLOR = Color(0xFFE8E8EA)
