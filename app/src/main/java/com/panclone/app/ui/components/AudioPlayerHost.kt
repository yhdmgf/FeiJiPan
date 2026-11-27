package com.panclone.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 音频播放页外壳：任意位置下拉跟手收回，四周大圆角，收回途中能看到下方页面。
 * 不做上滑展开；迷你条点击才进入播放页。
 */
@Composable
fun AudioPlayerHost(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var heightPx by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val anim = remember { Animatable(heightPx) }
    val enterAnim = remember { Animatable(0f) }

    LaunchedEffect(anim.value) { offsetY = anim.value.coerceAtLeast(0f) }
    LaunchedEffect(Unit) {
        enterAnim.animateTo(1f, tween(260))
    }

    val thresholdPx = (heightPx * 0.22f).coerceAtLeast(120f)
    val progress = if (heightPx > 0f) (offsetY / heightPx).coerceIn(0f, 1f) else 0f
    val corner = 22.dp + (14.dp * progress)

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat() }
            .graphicsLayer {
                translationY = offsetY
                val e = enterAnim.value.coerceIn(0f, 1f)
                alpha = e * (1f - 0.12f * progress)
                val s = 0.94f + 0.06f * e
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(corner))
            .pointerInput(Unit) {
                var first = true
                var velocity = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        first = true
                        velocity = 0f
                    },
                    onVerticalDrag = { _, delta ->
                        var applied = delta
                        if (first) {
                            first = false
                            applied = if (applied > 0f) (applied - touchSlop).coerceAtLeast(0f) else applied
                        }
                        velocity = applied
                        scope.launch {
                            anim.snapTo((anim.value + applied).coerceAtLeast(0f))
                        }
                    },
                    onDragEnd = {
                        val dismiss = velocity > with(density) { 300.dp.toPx() } ||
                            anim.value > thresholdPx
                        if (dismiss) {
                            scope.launch {
                                // 收回到迷你播放器位置
                                anim.animateTo(heightPx, spring(1f, 360f, 1f))
                                onDismiss()
                            }
                        } else {
                            scope.launch { anim.animateTo(0f, spring(1f, 360f, 1f)) }
                        }
                    },
                    onDragCancel = {
                        scope.launch { anim.animateTo(0f, spring(1f, 360f, 1f)) }
                    },
                )
            },
    ) {
        content()
    }
}
