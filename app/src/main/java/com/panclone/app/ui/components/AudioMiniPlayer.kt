package com.panclone.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.panclone.app.player.AudioPlaybackManager
import com.panclone.app.ui.screen.player.NowPlayingAlbumArtShape
import com.panclone.app.ui.theme.LocalAppColors
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 底栏上方的迷你播放器。
 *
 * 手势只作用于整条胶囊：横向跟手位移用于移除，纵向跟手位移用于展开完整播放器；
 * 图标按钮不享用拖动手势，所以上一曲/播放/下一曲点击始终有效。
 * 展开进度通过 progress 回调交给调用方，实现真正的“跟手展开”。
 */
@Composable
fun AudioMiniPlayer(
    title: String,
    artwork: Any?,
    progress: Float,
    isPlaying: Boolean,
    backdrop: Backdrop,
    onOpen: () -> Unit,
    onDismissed: () -> Unit,
    onExpandProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val light = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val container = if (light) Color(0xFFFAFAFA).copy(alpha = 0.55f)
    else Color(0xFF121212).copy(alpha = 0.55f)
    val scope = rememberCoroutineScope()
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .height(64.dp)
            .graphicsLayer {
                translationX = offsetX
                alpha = (1f - abs(offsetX) / 420f).coerceIn(0.2f, 1f)
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                onDrawSurface = { drawRect(container) },
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> offsetX = (offsetX + dragAmount).coerceAtLeast(0f) },
                    onDragEnd = {
                        if (offsetX > 150f) {
                            AudioPlaybackManager.dismissAndStop()
                            onDismissed()
                        }
                        scope.launch { offsetX = 0f }
                    },
                    onDragCancel = { scope.launch { offsetX = 0f } },
                )
            }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotatingAlbumArt(model = artwork, isPlaying = isPlaying, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                title.ifBlank { "正在播放" },
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { AudioPlaybackManager.previous() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.SkipPrevious, "上一曲", tint = colors.textPrimary)
            }
            IconButton(onClick = { AudioPlaybackManager.playOrPause() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "播放/暂停",
                    tint = colors.accent,
                    modifier = Modifier.size(25.dp),
                )
            }
            IconButton(onClick = { AudioPlaybackManager.next() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.SkipNext, "下一曲", tint = colors.textPrimary)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(colors.accent),
        )
    }
}

@Composable
fun RotatingAlbumArt(
    model: Any?,
    isPlaying: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            rotation.animateTo(
                rotation.value + 360f,
                animationSpec = tween(24_000, easing = LinearEasing),
            )
            rotation.snapTo(rotation.value % 360f)
        }
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation.value }
            .clip(NowPlayingAlbumArtShape.Waved)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxSize(0.48f),
            )
        }
    }
}
