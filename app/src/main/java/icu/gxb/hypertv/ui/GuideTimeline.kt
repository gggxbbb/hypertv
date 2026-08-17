package icu.gxb.hypertv.ui

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 节目表（EPG Guide）时间轴纯逻辑（ticket 10）。全部函数无副作用，可 JVM 单测：
 *
 * - 节目条水平布局：给定时间窗口与节目起止，计算条在网格中的 x 偏移与宽度
 * - 窗口移动与小时对齐：左右键滚动时间窗口、打开时以当前小时为中心 ±3 小时
 * - 当前节目查找：Info 浮层取"正在播放"的节目
 * - 时间格式化：java.time（minSdk 28 OK），避免 SimpleDateFormat 线程问题
 *
 * 所有时间戳为 epoch 毫秒；时间窗口 [windowStart, windowEnd)。
 */

/**
 * EPG 显示时区：固定北京时间 GMT+8（用户需求 2026-08-17）。
 *
 * XMLTV 源（如 epg.51zmt.top）时间标记 +0800，解析为 epoch 后按此时区显示；
 * 不依赖系统时区（模拟器/真机可能为 GMT 等，会导致时间轴差 8 小时）。
 */
val EPG_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 时间窗口总时长（小时）：以当前小时为中心 ±[WINDOW_CENTER_HOURS] */
const val WINDOW_DURATION_HOURS = 6

/** 打开节目表时窗口中心相对当前小时的偏移 */
const val WINDOW_CENTER_HOURS = 3

/** 左右键一次滚动的小时数 */
const val WINDOW_STEP_HOURS = 1

/** Guide 单页加载频道数（翻页追加，避免 5000 频道全量查询） */
const val GUIDE_PAGE_SIZE = 50

/** 一小时毫秒数 */
const val HOUR_MS = 3_600_000L

/** 六小时窗口毫秒数 */
val WINDOW_DURATION_MS: Long = WINDOW_DURATION_HOURS * HOUR_MS

/** 节目条在网格中的水平位置：x 为距窗口起始的像素偏移，width 为条宽（像素）。 */
data class ProgramLayout(
    val x: Float,
    val width: Float,
)

/**
 * 把毫秒时间戳向下对齐到整点（本地时区）。例如 14:37 → 14:00。
 */
fun alignToHour(ms: Long, zone: ZoneId = EPG_ZONE): Long {
    val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    return zoned.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() +
        zoned.hour * HOUR_MS
}

/**
 * Guide 打开时的默认窗口起始：当前小时向前偏移 [WINDOW_CENTER_HOURS] 小时。
 * 例如 now=14:37 → 窗口 [11:00, 17:00)（以 14 点为中心 ±3 小时）。
 */
fun guideWindowStartFor(nowMs: Long, zone: ZoneId = EPG_ZONE): Long =
    alignToHour(nowMs, zone) - WINDOW_CENTER_HOURS * HOUR_MS

/**
 * 左右键移动时间窗口：起始时间整体平移 [deltaHours]（可为负）。
 */
fun moveGuideWindow(windowStartMs: Long, deltaHours: Int): Long =
    windowStartMs + deltaHours * HOUR_MS

/**
 * 计算节目条在网格中的布局。窗口与节目无交集返回 null（条不可见，跳过绘制）。
 *
 * @param windowStartMs 时间窗口起始（毫秒）
 * @param windowEndMs 时间窗口结束（毫秒）
 * @param programStart 节目开始（毫秒）
 * @param programEnd 节目结束（毫秒）
 * @param gridWidthPx 网格总宽度（像素）
 */
fun layoutProgram(
    windowStartMs: Long,
    windowEndMs: Long,
    programStart: Long,
    programEnd: Long,
    gridWidthPx: Float,
): ProgramLayout? {
    val durationMs = (windowEndMs - windowStartMs).toDouble()
    if (durationMs <= 0 || gridWidthPx <= 0) return null

    // 窗口内可见的节目区间（裁剪到窗口边界）
    val visibleStart = maxOf(programStart.toDouble(), windowStartMs.toDouble())
    val visibleEnd = minOf(programEnd.toDouble(), windowEndMs.toDouble())
    if (visibleStart >= visibleEnd) return null

    val xFraction = (visibleStart - windowStartMs) / durationMs
    val widthFraction = (visibleEnd - visibleStart) / durationMs

    val x = (xFraction * gridWidthPx).toFloat()
    // 末尾越界部分整体超出一像素时缩回，避免条超出右边界（浮点误差兜底）
    val width = ((xFraction + widthFraction) * gridWidthPx).coerceAtMost(gridWidthPx.toDouble())
        .minus(xFraction * gridWidthPx)
        .toFloat()
    if (width <= 0f) return null
    return ProgramLayout(x = x, width = width)
}

/**
 * 在 [programs]（按 startTime 升序）中查找"正在播放"的节目：
 * startTime <= now < endTime。无则返回 null（频道未匹配 EPG 或节目间隙）。
 */
fun findCurrentProgram(
    programs: List<EpgProgramEntity>,
    nowMs: Long,
): EpgProgramEntity? = programs.firstOrNull { it.startTime <= nowMs && nowMs < it.endTime }

/** 时间窗口的起止时间文本（如 "08:00 - 14:00"），用于 Guide 头部。 */
fun formatWindowRange(windowStartMs: Long, durationMs: Long, zone: ZoneId = EPG_ZONE): String =
    "${formatTime(windowStartMs, zone)} - ${formatTime(windowStartMs + durationMs, zone)}"

/** 时间戳 → "HH:mm"（本地时区）。 */
fun formatTime(ms: Long, zone: ZoneId = EPG_ZONE): String {
    val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    return String.format("%02d:%02d", t.hour, t.minute)
}

/** 时间戳 → "HH:00"（时间轴刻度用）。 */
fun formatHourTick(ms: Long, zone: ZoneId = EPG_ZONE): String {
    val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    return String.format("%02d:00", t.hour)
}
