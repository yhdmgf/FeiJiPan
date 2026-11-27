package com.panclone.app.ui.screen.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.panclone.app.MainActivity
import com.panclone.app.data.model.PanFile
import kotlinx.coroutines.flow.MutableStateFlow
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.util.formatDuration

private val VipGold = Color(0xFFFFC94A)

/** 在线视频播放：ExoPlayer + 液态玻璃风格控制层。 */
@Composable
fun PlayerScreen(
    file: PanFile?,
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val pipFlow = remember(activity) { activity?.pipMode ?: MutableStateFlow(false) }
    val pipMode by pipFlow.collectAsState()

    LaunchedEffect(file) { file?.let { vm.load(it) } }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lockedLandscape by remember { mutableStateOf(false) }

    // 播放器：有 url 才创建；url 变化（切档/重试/失败）自动释放重建。
    val player = remember(state.url) {
        if (state.url.isBlank()) null else ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(state.url))
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(player, state.resumePositionMs, speed) {
        if (player == null) return@LaunchedEffect
        if (state.resumePositionMs > 0 && player.duration > 0) {
            player.seekTo(state.resumePositionMs.coerceAtMost(player.duration))
        }
        player.setPlaybackSpeed(speed)
    }

    BackHandler {
        if (pipMode) return@BackHandler
        onBack()
    }

    LaunchedEffect(isPlaying, activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.setPictureInPictureParams(videoPipParams(autoEnter = isPlaying))
        }
    }

    LaunchedEffect(player) {
        while (player != null) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            if (!dragging) sliderPos = if (duration > 0) position.toFloat() / duration else 0f
            vm.rememberProgress(position, duration)
            kotlinx.coroutines.delay(500)
        }
    }

    DisposableEffect(player) {
        onDispose {
            player?.release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity?.setPictureInPictureParams(videoPipParams(autoEnter = false))
            }
            (ctx as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
            )
            // 点击切换控制层
            Box(Modifier.fillMaxSize().clickable { if (!pipMode) controlsVisible = !controlsVisible })
        }

        if (state.preparing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (state.error.isNotBlank()) {
            Text(
                state.error + "\n点击下方画质可重试",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        if (state.notice.isNotBlank()) {
            Text(
                state.notice,
                color = VipGold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC1C1C1E))
                    .clickable { vm.clearNotice() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (controlsVisible && !pipMode) {
            // 顶部栏（错误态也显示返回）
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    state.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (player != null) {
                    IconButton(onClick = {
                        lockedLandscape = !lockedLandscape
                        (ctx as? Activity)?.requestedOrientation =
                            if (lockedLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }) {
                        Icon(Icons.Default.Fullscreen, "横屏", tint = Color.White)
                    }
                }
            }

            // 底部控制条（错误态也显示画质按钮，可点重试）
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xCC1C1C1E))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (player != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = sliderPos,
                            onValueChange = { v -> sliderPos = v; dragging = true },
                            onValueChangeFinished = {
                                dragging = false
                                player.seekTo((sliderPos * duration).toLong())
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (isPlaying) player.pause() else player.play()
                        }) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "播放/暂停",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(0.5f, 1f, 1.5f, 2f).forEach { sp ->
                                Text(
                                    "${sp}x",
                                    color = if (speed == sp) colors.accent else Color.White.copy(alpha = 0.7f),
                                    fontWeight = if (speed == sp) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .clickable { speed = sp; player.setPlaybackSpeed(sp) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text("正在准备播放…", color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium)
                }
                // 画质单独一行，倍速下方；高亮真实生效档；VIP 档金色
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("画质", color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 6.dp))
                    state.qualityOptions.forEach { q ->
                        val active = state.quality == q
                        val isVip = q == 2160 || q == 720
                        val label = when (q) { 0 -> "原画"; 2160 -> "2K"; else -> "${q}P" }
                        // 只有当前正在播放的 VIP 档才显示金色；未播放的一律普通白色
                        val color = when {
                            active && isVip -> VipGold
                            active -> colors.accent
                            else -> Color.White.copy(alpha = 0.7f)
                        }
                        Text(
                            label,
                            color = color,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { vm.setQuality(q, player?.currentPosition ?: 0L) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun videoPipParams(autoEnter: Boolean): PictureInPictureParams =
    PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(autoEnter)
                setSeamlessResizeEnabled(true)
            }
        }
        .build()
