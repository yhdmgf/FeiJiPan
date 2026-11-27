package com.panclone.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.panclone.app.data.model.ThemeMode

/**
 * iOS 26 风格设计系统。
 *
 * 取色参照 iOS 系统：分组背景（groupedBackground）、卡片（secondaryGroupedBackground）、
 * 系统蓝（systemBlue）、分隔线（separator）。深浅两套，跟随系统或手动切换。
 */
data class AppColors(
    /** 页面底色：iOS 分组背景 */
    val pageBackground: Color,
    /** 卡片 / 列表单元底色 */
    val cardBackground: Color,
    /** 卡片内嵌的次级底色 */
    val cardSecondary: Color,
    /** 顶栏 / 底栏这类浮层底色（半透明，配模糊） */
    val barBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** 按压态叠加色 */
    val press: Color,
    val isDark: Boolean,
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        pageBackground = Color(0xFFFFFFFF),
        cardBackground = Color(0xFFFFFFFF),
        cardSecondary = Color(0xFFF2F2F7),
        barBackground = Color(0xE6F9F9F9),
        textPrimary = Color(0xFF000000),
        textSecondary = Color(0xFF8A8A8E),
        textTertiary = Color(0xFFC7C7CC),
        separator = Color(0x4D3C3C43),
        accent = Color(0xFF007AFF),
        onAccent = Color.White,
        danger = Color(0xFFFF3B30),
        success = Color(0xFF34C759),
        warning = Color(0xFFFF9500),
        press = Color(0x22000000),
        isDark = false,
    )
}

private val LightColors = AppColors(
    pageBackground = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    cardSecondary = Color(0xFFF2F2F7),
    barBackground = Color(0xF2F9F9F9),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF8A8A8E),
    textTertiary = Color(0xFFC7C7CC),
    separator = Color(0x4D3C3C43),
    accent = Color(0xFF007AFF),
    onAccent = Color.White,
    danger = Color(0xFFFF3B30),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    press = Color(0x22000000),
    isDark = false,
)

private val DarkColors = AppColors(
    pageBackground = Color(0xFF000000),
    cardBackground = Color(0xFF1C1C1E),
    cardSecondary = Color(0xFF2C2C2E),
    barBackground = Color(0xF21C1C1E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF48484A),
    separator = Color(0x4D545458),
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
    danger = Color(0xFFFF453A),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    press = Color(0x2EFFFFFF),
    isDark = true,
)

/** iOS 圆角规格 */
object Radius {
    val card = 14.dp
    val dialog = 26.dp
    val sheet = 34.dp
    val chip = 10.dp
    val bar = 30.dp
}

@Composable
fun AppTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors

    val m3 = if (dark) darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        surface = colors.cardBackground,
        background = colors.pageBackground,
        onSurface = colors.textPrimary,
        error = colors.danger,
    ) else lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        surface = colors.cardBackground,
        background = colors.pageBackground,
        onSurface = colors.textPrimary,
        error = colors.danger,
    )

    MaterialTheme(colorScheme = m3) {
        CompositionLocalProvider(LocalAppColors provides colors, content = content)
    }
}

/**
 * 全局页面 backdrop：AppRoot 注入，任何浮层（标题栏/操作条/弹窗）都能采样它做真模糊。
 */
val LocalPageBackdrop = staticCompositionLocalOf<com.kyant.backdrop.Backdrop> {
    com.kyant.backdrop.backdrops.emptyBackdrop()
}
