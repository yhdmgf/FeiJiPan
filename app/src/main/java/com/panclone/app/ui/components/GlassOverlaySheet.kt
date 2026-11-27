package com.panclone.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

private val GlassSheetShape = RoundedCornerShape(26.dp)

@Composable
private fun sheetColors(): Pair<Color, Color> {
    val light = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    return if (light) {
        Color(0xFFFAFAFA).copy(alpha = 0.55f) to Color(0xFF000000)
    } else {
        Color(0xFF121212).copy(alpha = 0.55f) to Color(0xFFFFFFFF)
    }
}

@Composable
private fun GlassSheetFrame(
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
        label = "glassSheetAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "glassSheetScale",
    )
    LaunchedEffect(Unit) { visible = true }
    BackHandler { onDismiss() }

    val (container, _) = sheetColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f * alpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 26.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }
                .clip(GlassSheetShape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { GlassSheetShape },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    onDrawSurface = { drawRect(container) },
                ),
            content = { content() },
        )
    }
}

@Composable
fun GlassActionSheet(
    backdrop: Backdrop,
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val (_, contentColor) = sheetColors()
    GlassSheetFrame(backdrop = backdrop, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            actions.forEach { (label, onClick) ->
                val rowInteraction = remember { MutableInteractionSource() }
                val rowPressed by rowInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (rowPressed) contentColor.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = rowInteraction,
                            indication = null,
                        ) { onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 15.sp, color = contentColor)
                }
            }
            val cancelInteraction = remember { MutableInteractionSource() }
            val cancelPressed by cancelInteraction.collectIsPressedAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (cancelPressed) contentColor.copy(alpha = 0.10f) else Color.Transparent,
                    )
                    .clickable(
                        interactionSource = cancelInteraction,
                        indication = null,
                    ) { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
            }
        }
    }
}

@Composable
fun GlassCreateFolderSheet(
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val (_, contentColor) = sheetColors()
    var name by remember { mutableStateOf("") }
    GlassSheetFrame(backdrop = backdrop, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "新建文件夹",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("文件夹名称", color = contentColor.copy(alpha = 0.55f)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = contentColor),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                val cancelInteraction = remember { MutableInteractionSource() }
                val cancelPressed by cancelInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (cancelPressed) contentColor.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = cancelInteraction,
                            indication = null,
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("取消", color = contentColor.copy(alpha = 0.75f), fontSize = 15.sp) }
                Spacer(Modifier.width(12.dp))
                val createInteraction = remember { MutableInteractionSource() }
                val createPressed by createInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(Capsule())
                        .background(
                            if (createPressed) contentColor.copy(alpha = 0.14f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = createInteraction,
                            indication = null,
                        ) {
                            onCreate(if (name.isBlank()) "新建文件夹" else name.trim())
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("创建", color = contentColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
fun GlassConfirmDialog(
    backdrop: Backdrop,
    title: String,
    message: String? = null,
    confirmText: String = "确定",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (_, contentColor) = sheetColors()
    GlassSheetFrame(backdrop = backdrop, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    fontSize = 13.sp, color = contentColor.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                val cancelInteraction = remember { MutableInteractionSource() }
                val cancelPressed by cancelInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (cancelPressed) contentColor.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = cancelInteraction,
                            indication = null,
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("取消", color = contentColor.copy(alpha = 0.75f), fontSize = 15.sp) }
                Spacer(Modifier.width(12.dp))
                val confirmInteraction = remember { MutableInteractionSource() }
                val confirmPressed by confirmInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(Capsule())
                        .background(
                            if (confirmPressed) contentColor.copy(alpha = 0.14f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = confirmInteraction,
                            indication = null,
                        ) { onConfirm() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(confirmText,
                        color = if (destructive) Color(0xFFFF453A) else contentColor,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}
