package com.panclone.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.panclone.app.ui.theme.LocalAppColors

/**
 * 文件页悬浮操作按钮。
 *
 * 点击后不再弹窗：在按钮正上方展开一块与底栏同参数的玻璃面板
 * （vibrancy + blur + lens），提供「上传文件 / 新建文件夹」两项。
 * 必须绘制在捕获层之外，否则 backdrop 会自引用。
 */
@Composable
fun FloatingActionFab(
    backdrop: Backdrop,
    onUpload: () -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val light = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() >= 0.5f
    // 白天模式加一点淡蓝玻璃效果
    val container = if (light) Color(0xFFDCEBFF).copy(alpha = 0.62f)
    else Color(0xFF121212).copy(alpha = 0.55f)
    var expanded by remember { mutableStateOf(false) }
    val rotate by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "fabRotate",
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "fabPanel",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (panelAlpha > 0.01f) {
            Column(
                Modifier
                    .width(184.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(22.dp) },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        },
                        onDrawSurface = { drawRect(container) },
                    )
                    .padding(6.dp),
            ) {
                PanelAction("上传文件", Icons.Outlined.Upload) {
                    expanded = false
                    onUpload()
                }
                PanelAction("新建文件夹", Icons.Outlined.CreateNewFolder) {
                    expanded = false
                    onCreateFolder()
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    rotationZ = rotate
                }
                .clip(CircleShape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = { drawRect(container) },
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = if (expanded) "收起" else "上传/新建",
                tint = colors.accent,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun PanelAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) colors.press else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(Capsule())
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
