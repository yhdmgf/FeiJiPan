package com.panclone.app.ui.screen.music

import androidx.compose.animation.core.animateFloat

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.border
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.repo.FileRepository
import com.panclone.app.player.AudioPlaybackManager
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.util.formatSize

/**
 * 音乐页：直接列出网盘根目录的音频文件，点击即用同一套 MediaSession 播放。
 * 与全屏音频播放器/迷你播放器共享队列，切换 tab 不会中断播放。
 */
@Composable
fun MusicScreen(onPlay: (List<PanFile>, Int) -> Unit) {
    val colors = LocalAppColors.current
    val playback by AudioPlaybackManager.state.collectAsState()
    val artwork by AudioPlaybackManager.artwork.collectAsState()
    var files by remember { mutableStateOf<List<PanFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current
    // 音频索引属于可重建缓存，放 cacheDir，不进用户数据目录。
    val cacheFile = remember { java.io.File(context.cacheDir, "music_index.txt") }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 打开列表即后台预取缺的封面（已有磁盘缓存的自动跳过）。
    LaunchedEffect(files) {
        if (files.isNotEmpty()) AudioPlaybackManager.startArtworkPrefetch(files, batch = 20)
    }

    // 滑动经过的行：没有封面的随手触发单首获取，结果进磁盘缓存。
    // key 带 files：列表从缓存换成真实数据后重启 collect，闭包里的 files 保持新鲜。
    LaunchedEffect(files, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { indexes ->
                indexes.forEach { i ->
                    files.getOrNull(i)?.let { AudioPlaybackManager.prefetchArtwork(it) }
                }
            }
    }

    // 触发一次“加载/重扫”：0=启动，>0=下拉刷新次数。
    var scanTick by remember { mutableStateOf(0) }

    LaunchedEffect(scanTick) {
        // 启动先用缓存秒开；手动刷新(scTick>0)强制回源。
        if (scanTick == 0) {
            val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    cacheFile.readLines()
                        .filter { it.contains('\t') }
                        .map { line ->
                            val p = line.split('\t')
                            PanFile(
                                fsId = p[0].toLong(),
                                path = p[1],
                                name = p[2],
                                size = p.getOrNull(3)?.toLongOrNull() ?: 0L,
                                mtime = p.getOrNull(4)?.toLongOrNull() ?: 0L,
                                isDir = 0,
                            )
                        }
                }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) {
                files = cached
                loading = false
                return@LaunchedEffect
            }
        }

        // 用网页版分类接口：/api/categorylist?category=2，一次拿全，比逐关键词 search 快得多。
        error = ""
        val repo = FileRepository(App.instance.api)
        when (val r = repo.audioList()) {
            is FileRepository.Result.Ok -> {
                val sorted = r.data
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        cacheFile.writeText(
                            sorted.joinToString("\n") {
                                listOf(it.fsId, it.path, it.name, it.size, it.mtime).joinToString("\t")
                            },
                        )
                    }
                }
                files = sorted
            }
            is FileRepository.Result.Err -> error = r.message
        }
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("音乐", color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (files.isEmpty()) "点击右上角刷新以获取网盘音频"
                        else "网盘音频 · ${files.size} 首",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
                // 刷新放在右上角：点击才重新全盘搜索并覆盖缓存。
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                         .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !loading) { scanTick++ },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新音频列表",
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                error.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, color = colors.danger)
                }
                files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无音频文件", color = colors.textSecondary)
                }
                else -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    state = listState,
                ) {
                    itemsIndexed(files, key = { _, file -> file.fsId }) { index, file ->
                        val playing = playback.mediaId == file.fsId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (playing) colors.accent.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                )
                                .clickable { onPlay(files, index) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 封面三级回退：播放中内存 → 磁盘缓存（打开列表直接显示）→ 无占位
                            val diskCover = remember(file.fsId) {
                                runCatching {
                                    com.panclone.app.data.CacheStore.artworkFile(context, file.fsId)
                                        .takeIf { it.exists() }?.readBytes()
                                }.getOrNull()
                            }
                            val cover: Any? = artwork[file.fsId] ?: diskCover ?: thumbUrlOf(file)
                            Box(
                                Modifier
                                    .size(50.dp)
                                    .clip(com.panclone.app.ui.screen.player.NowPlayingAlbumArtShape.Waved)
                                    .background(colors.cardSecondary),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(Icons.Default.MusicNote, null, tint = colors.accent)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (playing) "正在播放 · ${formatSize(file.size)}"
                                    else "第 ${index + 1} 首 · ${formatSize(file.size)}",
                                    color = if (playing) colors.accent else colors.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (playing) {
                                Text(
                                    "播放中",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colors.accent)
                                        .padding(horizontal = 9.dp, vertical = 5.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    tint = colors.textTertiary,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(150.dp)) }
                }
            }
        }
    }
}

/** 下拉刷新：触发距离与保持高度（px）。 */
