package com.panclone.app.ui.screen.player

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.panclone.app.data.model.PanFile
import com.panclone.app.player.AudioPlaybackManager
import com.panclone.app.ui.components.RotatingAlbumArt
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.util.formatDuration
import com.panclone.app.util.formatSize

/** MediaSessionService 驱动的完整音频界面；离开页面后继续后台播放。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    files: List<PanFile>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val playback by AudioPlaybackManager.state.collectAsState()
    val artwork by AudioPlaybackManager.artwork.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val sleepAt by AudioPlaybackManager.sleepAt.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(0f) }

    // 从主界面点进来时队列已在 AppRoot 建立，这里只兜底处理“尚无可播曲目”的情况。
    LaunchedEffect(files, initialIndex) {
        if (!playback.hasMedia) {
            AudioPlaybackManager.playQueue(files, initialIndex.coerceIn(files.indices))
        }
    }

    val queue = files
    val currentFile = queue.firstOrNull { it.fsId == playback.mediaId }
        ?: queue.getOrNull(playback.index.coerceIn(queue.indices))
        ?: files.getOrNull(initialIndex.coerceIn(files.indices))
    val currentId = currentFile?.fsId ?: 0L
    // 切歌时先保留上一张封面，等新封面到位再替换，避免整页闪一下。
    var lastCover by remember { mutableStateOf<Any?>(null) }
    val resolvedCover: Any? = artwork[currentId] ?: currentFile?.let(::thumbUrlOf)
    LaunchedEffect(resolvedCover) { if (resolvedCover != null) lastCover = resolvedCover }
    val cover: Any? = resolvedCover ?: lastCover
    val duration = playback.durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0) playback.positionMs.toFloat() / duration else 0f
    LaunchedEffect(progress, dragging) {
        if (!dragging) sliderValue = progress
    }

    // 无手势收回：只通过返回按钮退出；保留完整播放页内容与功能。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07111F)),
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.28f; scaleY = 1.28f }
                    .blur(48.dp),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF18324F), Color(0xFF07111F), Color.Black),
                        ),
                    ),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)))

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                "音频预览",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable { showQueue = true }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.QueueMusic, "播放列表", tint = Color(0xFF70B7FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${playback.index + 1} / ${queue.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showSleep = true }) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = "定时关闭",
                    tint = if (sleepAt > 0) Color(0xFF70B7FF) else Color.White.copy(alpha = 0.8f),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RotatingAlbumArt(
                model = cover,
                isPlaying = playback.isPlaying,
                size = 250.dp,
            )
            if (playback.loadingQueue && !playback.hasMedia) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                currentFile?.name ?: playback.title.ifBlank { "正在准备音频" },
                color = Color.White,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (playback.error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(playback.error, color = Color(0xFFFF8A80), fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { dragging = true; sliderValue = it },
                onValueChangeFinished = {
                    dragging = false
                    if (duration > 0) AudioPlaybackManager.seekTo((sliderValue * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(playback.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(formatDuration(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = AudioPlaybackManager::previous, modifier = Modifier.size(58.dp)) {
                    Icon(Icons.Default.SkipPrevious, "上一曲", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                IconButton(
                    onClick = AudioPlaybackManager::playOrPause,
                    enabled = playback.hasMedia,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "播放/暂停",
                        tint = Color(0xFF07111F),
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(onClick = AudioPlaybackManager::next, modifier = Modifier.size(58.dp)) {
                    Icon(Icons.Default.SkipNext, "下一曲", tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }
        }
    }

    if (showSleep) {
        val minutes = listOf(10, 20, 30, 45, 60)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSleep = false },
            containerColor = Color(0xFF141C28),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.75f),
            title = { Text("定时关闭", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    minutes.forEach { m ->
                        Text(
                            "$m 分钟",
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    AudioPlaybackManager.setSleepTimer(m)
                                    showSleep = false
                                }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                        )
                    }
                    if (sleepAt > 0) {
                        Text(
                            "关闭定时",
                            color = Color(0xFFFF8A80),
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AudioPlaybackManager.setSleepTimer(0)
                                    showSleep = false
                                }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Text(
                    "取消",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.clickable { showSleep = false }.padding(12.dp),
                )
            },
        )
    }

    if (showQueue) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = sheetState,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            dragHandle = null,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 650.dp)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)),
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
                            .blur(52.dp),
                    )
                }
                Box(Modifier.fillMaxSize().background(Color(0xE6111C2B)))
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .size(width = 42.dp, height = 5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.25f))
                            .align(Alignment.CenterHorizontally),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("播放列表", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "当前目录共 ${queue.size} 首 · 点击切换，列表保持展开",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.sp,
                            )
                        }
                        IconButton(onClick = { showQueue = false }) {
                            Icon(Icons.Default.Close, "收起", tint = Color.White)
                        }
                    }
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp),
                    ) {
                        itemsIndexed(queue, key = { _, item -> item.fsId }) { queueIndex, item ->
                            val selected = item.fsId == playback.mediaId
                            val itemCover: Any? = artwork[item.fsId] ?: thumbUrlOf(item)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (selected) Color(0xFF1685FF).copy(alpha = 0.20f)
                                        else Color.White.copy(alpha = 0.04f),
                                    )
                                    .clickable { AudioPlaybackManager.playIndex(queueIndex) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .clip(NowPlayingAlbumArtShape.Waved)
                                        .background(Color.White.copy(alpha = 0.10f))
                                        .then(
                                            if (selected) Modifier.border(2.dp, Color(0xFF1685FF), NowPlayingAlbumArtShape.Waved)
                                            else Modifier,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (itemCover != null) {
                                        AsyncImage(
                                            model = itemCover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.7f))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (selected) "正在播放 · ${formatSize(item.size)}"
                                        else "第 ${queueIndex + 1} 首 · ${formatSize(item.size)}",
                                        color = if (selected) Color(0xFF70B7FF) else Color.White.copy(alpha = 0.52f),
                                        fontSize = 12.sp,
                                    )
                                }
                                if (selected) {
                                    Text(
                                        "播放中",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF1685FF))
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, "播放", tint = Color.White.copy(alpha = 0.58f))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

