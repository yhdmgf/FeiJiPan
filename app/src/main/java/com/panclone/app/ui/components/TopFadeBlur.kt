package com.panclone.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 顶栏 / 多选栏 / 重命名栏共用的玻璃底。
 *
 * 白天：与悬浮底栏外层完全一致（vibrancy + blur + lens + 默认高光/内阴影/投影 + 半透明面），
 * 因此能看到同样的边线与质感。
 * 夜间：保持原有纯模糊，不染色、无高光、无阴影、无边线。
 */
@Composable
fun TopFadeBlur(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val light = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val container = if (light) Color(0xFFFAFAFA).copy(alpha = 0.55f)
    else Color(0xFF121212).copy(alpha = 0.55f)

    Box(
        modifier
            .clipToBounds()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(20.dp.toPx()) },
                highlight = null,
                shadow = null,
                innerShadow = null,
                onDrawSurface = {},
            ),
    )
}
