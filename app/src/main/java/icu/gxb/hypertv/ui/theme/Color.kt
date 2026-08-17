package icu.gxb.hypertv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 黑白灰统一配色（2026-08-17 用户要求：整体最简单的黑白灰，不再区分亮暗模式）。
 * 电视端深色基底：黑背景 + 灰表面 + 白/浅灰文字。
 */
val TvBlack = Color(0xFF000000)
val TvSurface = Color(0xFF1E1E22) // 面板 / 卡片 / 浮层
val TvSurfaceVariant = Color(0xFF2C2C31) // 次级表面
val TvSurfaceHigh = Color(0xFF3A3A42) // 选中 / 强调表面（时间轴节目条）
val TvSurfaceFocus = Color(0xFF5E5E68) // 焦点表面（节目条聚焦）
val TvWhite = Color(0xFFE8E8EA) // 主文字 / primary
val TvGrey = Color(0xFF9E9EA6) // 次要文字
val TvOutline = Color(0xFF4A4A50) // 描边 / 分隔线
