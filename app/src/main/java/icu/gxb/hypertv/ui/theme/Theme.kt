package icu.gxb.hypertv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * HyperTV 主题：统一黑白灰配色，不再区分亮暗模式（用户需求，2026-08-17）。
 * 显式设置全部关键颜色，避免 Material 默认色（如暗色 onPrimary 深紫）泄漏。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HyperTVTheme(
    isInDarkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = darkColorScheme(
        primary = TvWhite,
        onPrimary = TvBlack,
        primaryContainer = TvSurfaceHigh,
        onPrimaryContainer = TvWhite,
        secondary = TvGrey,
        onSecondary = TvBlack,
        secondaryContainer = TvSurfaceVariant,
        onSecondaryContainer = TvWhite,
        tertiary = TvGrey,
        onTertiary = TvBlack,
        tertiaryContainer = TvSurfaceVariant,
        onTertiaryContainer = TvWhite,
        background = TvBlack,
        onBackground = TvWhite,
        surface = TvSurface,
        onSurface = TvWhite,
        surfaceVariant = TvSurfaceVariant,
        onSurfaceVariant = TvGrey,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
