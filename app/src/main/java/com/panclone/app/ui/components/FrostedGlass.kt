package com.panclone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 真·模糊玻璃容器：对 backdrop（背后页面内容）做实时模糊 + 提亮 + 高光。
 *
 * 与 LiquidGlassSurface 的区别：那个只是半透明色块，这个是真的在采样并模糊背景。
 * 用在弹窗、标题栏、悬浮操作条等浮层上，保证全局视觉一致。
 */
@Composable
fun FrostedGlass(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    blurRadius: Dp = 24.dp,
    tint: Color? = null,
    tintAlpha: Float = 0.55f,
    showHighlight: Boolean = true,
    showShadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = tint ?: if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Box(
        modifier = modifier
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                },
                highlight = if (showHighlight) ({ Highlight.Default }) else null,
                shadow = if (showShadow) ({ Shadow() }) else null,
                onDrawSurface = { drawRect(surface.copy(alpha = tintAlpha)) },
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * 无 backdrop 的静态玻璃（用于 Dialog 内部 —— Dialog 有独立 window，采不到页面 backdrop）。
 * 用多层渐变模拟玻璃质感，避免纯色平板感。
 */
@Composable
fun StaticGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF2C2C2E) else Color(0xFFFDFDFD)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        base,
                        if (dark) Color(0xFF232325) else Color(0xFFF4F4F6),
                    )
                ),
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
