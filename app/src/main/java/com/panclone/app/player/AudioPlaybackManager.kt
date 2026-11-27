package com.panclone.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.net.PanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AudioPlaybackState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val loadingQueue: Boolean = false,
    val mediaId: Long = 0L,
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val index: Int = 0,
    val count: Int = 0,
    val queue: List<PanFile> = emptyList(),
    val error: String = "",
)

/**
 * UI 与 MediaSessionService 之间唯一的音频入口。
 *
 * 队列策略（滑动窗口）：播放时取当前位置前后共 WINDOW 首建立 Media3 队列；
 * 切到接近窗口边缘时，以当前曲为中心再取 WINDOW 首重建队列。
 * 已取到的直链留在内存 map 复用，窗口来回滑动不重复联网。
 * 上一曲/下一曲用 Media3 原生队列跳转，通知栏按钮由 Media3 稳定管理，
 * 播完自动续播也交给 Media3（REPEAT_MODE_OFF + 窗口末尾前主动补链）。
 */
object AudioPlaybackManager {
    private const val WINDOW = 10
    private const val EDGE = 2
    /** 直链最长复用 2 小时，超时视为过期、强制重取，避免播到失效链。 */
    private const val URL_TTL_MS = 2L * 60 * 60 * 1000
    private const val ARTWORK_MAX_SIDE = 320

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val urls = ConcurrentHashMap<Long, String>()
    private val urlTimes = ConcurrentHashMap<Long, Long>()
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()
    private val _artwork = MutableStateFlow<Map<Long, ByteArray>>(emptyMap())
    val artwork: StateFlow<Map<Long, ByteArray>> = _artwork.asStateFlow()

    private lateinit var appContext: Context
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var windowJob: Job? = null
    private var generation = 0L
    private var initialized = false
    private var allFiles: List<PanFile> = emptyList()
    private var winStart = 0
    private var winEnd = 0
    /** 边缘切歌时暂存方向，补链重建完成后再执行跳转。 */
    private var pendingStep = 0
    private var urlCacheLoaded = false
    private var prefetchJob: Job? = null
    private var lastPublishedId: Long = 0L

    /** 启动时载入上次窗口的直链缓存；带时间戳，过期的直接丢弃。 */
    private fun loadUrlCache() {
        if (urlCacheLoaded) return
        urlCacheLoaded = true
        val now = System.currentTimeMillis()
        runCatching {
            com.panclone.app.data.CacheStore.audioUrlCache(appContext).readLines().forEach { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) {
                    val id = p[0].toLongOrNull()
                    val time = p[1].toLongOrNull()
                    if (id != null && time != null && now - time <= URL_TTL_MS && p[2].isNotBlank()) {
                        urls[id] = p[2]
                        urlTimes[id] = time
                    }
                }
            }
        }
    }

    /** 移除原有缓存，全量写入当前窗口 10 首直链（带获取时间）。 */
    private fun saveWindowCache() {
        val file = com.panclone.app.data.CacheStore.audioUrlCache(appContext)
        val now = System.currentTimeMillis()
        val out = (winStart until winEnd).mapNotNull { i ->
            val f = allFiles.getOrNull(i) ?: return@mapNotNull null
            val u = urls[f.fsId]
            if (u.isNullOrBlank()) null else "${f.fsId}\t${urlTimes[f.fsId] ?: now}\t$u"
        }
        runCatching {
            if (file.exists()) file.delete()
            file.writeText(out.joinToString("\n"))
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        loadUrlCache()
        val token = SessionToken(appContext, ComponentName(appContext, AudioPlaybackService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener({
            runCatching { pending.get() }.onSuccess { mediaController ->
                if (controller != null) return@onSuccess
                controller = mediaController
                mediaController.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        publish()
                        recenterIfNeeded(player)
                    }
                    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                        val id = mediaController.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                        metadata.artworkData?.let { cacheArtwork(id, it) }
                    }
                })
                publish()
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "连接后台播放器失败")
            }
        }, ContextCompat.getMainExecutor(appContext))

        scope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) {
                    publish()
                    delay(1_000L)
                } else {
                    delay(5_000L)
                }
            }
        }
    }

    /** 从任意音频列表起播。fromMusicPage 保留为兼容参数，行为已统一。 */
    @Suppress("UNUSED_PARAMETER")
    fun playQueue(files: List<PanFile>, startIndex: Int, fromMusicPage: Boolean = false) {
        if (files.isEmpty()) return
        initialize(App.instance)
        val start = startIndex.coerceIn(files.indices)
        windowJob?.cancel()
        allFiles = files
        pendingStep = 0
        _state.value = _state.value.copy(
            queue = files,
            index = start,
            count = files.size,
            loadingQueue = true,
            error = "",
        )
        rebuild(start, playNow = true)
    }

    /**
     * 以 globalIndex 为中心取 WINDOW 首重建播放器队列，保留当前曲目与进度。
     * 直链命中内存 map 时不联网；未命中的才取链。
     */
    private fun rebuild(globalIndex: Int, playNow: Boolean) {
        val thisRun = ++generation
        windowJob?.cancel()
        windowJob = scope.launch {
            val player = awaitController()
            winStart = (globalIndex - WINDOW / 2).coerceIn(0, maxOf(0, allFiles.size - WINDOW))
            winEnd = minOf(winStart + WINDOW, allFiles.size)
            val target = allFiles.getOrNull(globalIndex) ?: return@launch
            val targetId = target.fsId

            // 1) 当前曲先取链、秒起播（补链重建时若正在播它则不动，只保留进度）。
            val alreadyPlaying = player.currentMediaItem?.mediaId == targetId.toString()
            if (!alreadyPlaying) {
                if (resolveUrl(target).isBlank()) {
                    _state.value = _state.value.copy(loadingQueue = false, error = "获取音频播放链接失败")
                    return@launch
                }
                if (thisRun != generation) return@launch
                player.setMediaItem(mediaItem(target))
                player.prepare()
                if (playNow) player.play()
                _state.value = _state.value.copy(hasMedia = true, loadingQueue = false, index = globalIndex)
                publish()
            }

            // 2) 后台并发取窗口内其余曲目直链（命中内存 map 不联网）。
            val keepPos = if (alreadyPlaying) player.currentPosition else 0L
            withContext(Dispatchers.IO) {
                val sem = Semaphore(5)
                coroutineScope {
                    (winStart until winEnd).map { i ->
                        async { sem.withPermit { resolveUrl(allFiles[i]) } }
                    }.awaitAll()
                }
            }
            if (thisRun != generation) return@launch

            // 3) 用完整窗口队列重建，保留当前曲目与进度。
            val items = (winStart until winEnd).mapNotNull { i ->
                val f = allFiles[i]
                if (urls[f.fsId].isNullOrBlank()) null else mediaItem(f)
            }
            if (items.isEmpty()) return@launch
            val localIdx = items.indexOfFirst { it.mediaId == targetId.toString() }
                .takeIf { it >= 0 }
                ?: 0
            player.setMediaItems(items, localIdx, keepPos)
            if (playNow && !alreadyPlaying) player.play()
            scope.launch(Dispatchers.IO) {
                items.forEachIndexed { i, _ ->
                    val f = allFiles.getOrNull(winStart + i) ?: return@forEachIndexed
                    urls[f.fsId]?.let { ensureArtwork(f, it) }
                }
            }
            _state.value = _state.value.copy(
                hasMedia = true,
                loadingQueue = false,
                index = globalIndex,
                count = allFiles.size,
            )
            saveWindowCache()
            publish()
            consumePendingStep(player)
        }
    }

    /** 当前曲接近窗口边缘且窗口还能滑动时，以当前曲为中心重建，补上同方向的歌。 */
    private fun recenterIfNeeded(player: Player) {
        if (allFiles.isEmpty() || player.mediaItemCount == 0) return
        val idx = player.currentMediaItemIndex
        val count = player.mediaItemCount
        val atHeadEdge = idx <= EDGE && winStart > 0
        val atTailEdge = idx >= count - 1 - EDGE && winEnd < allFiles.size
        if (!atHeadEdge && !atTailEdge) return
        val curId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val globalIndex = allFiles.indexOfFirst { it.fsId == curId }
        if (globalIndex < 0) return
        rebuild(globalIndex, playNow = false)
    }

    fun playOrPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun previous() = step(-1)
    fun next() = step(1)

    /** 上一曲/下一曲：窗口内有歌直接跳；在边缘则先补链重建，完成后再跳。 */
    private fun step(direction: Int) {
        val player = controller ?: return
        val idx = player.currentMediaItemIndex
        val count = player.mediaItemCount
        val canStep = if (direction < 0) idx > 0 else idx < count - 1
        if (canStep) {
            if (direction < 0) player.seekToPrevious() else player.seekToNext()
            recenterIfNeeded(player)
            return
        }
        // 已在窗口边缘：记住方向，重建窗口把当前曲放到中间后再跳。
        val curId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val globalIndex = allFiles.indexOfFirst { it.fsId == curId }
        if (globalIndex < 0) return
        pendingStep = direction
        rebuild(globalIndex, playNow = false)
    }

    private fun consumePendingStep(player: Player) {
        if (pendingStep == 0) return
        val dir = pendingStep
        pendingStep = 0
        val idx = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (dir < 0 && idx > 0) player.seekToPrevious()
        else if (dir > 0 && idx < count - 1) player.seekToNext()
    }

    /** 按完整列表索引切歌；不在当前窗口时以目标为中心重建。 */
    fun playIndex(index: Int) {
        val player = controller ?: return
        val file = allFiles.getOrNull(index) ?: return
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == file.fsId.toString()) {
                player.seekToDefaultPosition(i)
                player.play()
                return
            }
        }
        rebuild(index, playNow = true)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    private var sleepJob: Job? = null
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt.asStateFlow()

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepAt.value = 0L
            return
        }
        val until = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = until
        sleepJob = scope.launch {
            while (isActive && System.currentTimeMillis() < until) {
                delay(1_000L)
            }
            _sleepAt.value = 0L
            controller?.pause()
        }
    }

    /** 磁盘里是否已有该曲封面缓存（列表页用来决定是否需要预取） */
    fun hasCachedArtwork(id: Long): Boolean = artworkFile(id).exists()

    /**
     * 后台批量预取封面：跳过已有缓存的，逐首 Range 拉头部字节解析内嵌图。
     * 先拉 batch 首；之后每播完一首（切曲）放行下一首，直到全部完成。
     */
    fun startArtworkPrefetch(files: List<PanFile>, batch: Int = 20) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            val todo = files.filter { f ->
                _artwork.value[f.fsId] == null && !artworkFile(f.fsId).exists()
            }
            if (todo.isEmpty()) return@launch
            var remaining = batch
            var i = 0
            while (i < todo.size && isActive) {
                if (remaining == 0) {
                    onAdvance.acquire() // 首批用完后：每切一首歌放行下一首
                } else {
                    remaining--
                }
                val file = todo[i++]
                val url = resolveUrl(file)
                if (url.isBlank()) continue
                var (cover, tagSize) = ArtworkFetcher.fetchCover(url)
                if (cover == null && tagSize != null && tagSize > ArtworkFetcher.FIRST_RANGE_END) {
                    cover = ArtworkFetcher.fetchCover(url, ArtworkFetcher.RETRY_RANGE_END).first
                }
                if (cover != null) {
                    val ready = if (isArtworkCompressed(cover)) cover else compressArtwork(cover) ?: continue
                    putArtwork(file.fsId, ready)
                    runCatching {
                        artworkFile(file.fsId).parentFile?.mkdirs()
                        artworkFile(file.fsId).writeBytes(ready)
                    }
                }
            }
        }
    }

    /**
     * 播放切到下一曲时补一个预取许可。
     * 用 java.util.concurrent.Semaphore：release 超过初始 permits 不抛异常（kotlinx 会抛），
     * 多余许可自然累积，预取循环按需消费，天然适配"循环没跑时也切歌"的场景。
     */
    private val onAdvance = java.util.concurrent.Semaphore(0)
    private fun grantPrefetch() { onAdvance.release() }

    /** 单首按需获取（列表滑到哪拉到哪）：已有内存/磁盘缓存则跳过，结果写磁盘。 */
    fun prefetchArtwork(file: PanFile) {
        if (_artwork.value[file.fsId] != null || artworkFile(file.fsId).exists()) return
        if (onDemandInFlight.contains(file.fsId)) return
        // 快速滑动时限制同时进行的按需获取数，避免直链请求轰炸
        if (onDemandInFlight.size >= 3) return
        onDemandInFlight.add(file.fsId)
        scope.launch(Dispatchers.IO) {
            try {
                val url = resolveUrl(file)
                if (url.isBlank()) return@launch
                var (cover, tagSize) = ArtworkFetcher.fetchCover(url)
                if (cover == null && tagSize != null && tagSize > ArtworkFetcher.FIRST_RANGE_END) {
                    cover = ArtworkFetcher.fetchCover(url, ArtworkFetcher.RETRY_RANGE_END).first
                }
                if (cover != null) {
                    val ready = if (isArtworkCompressed(cover)) cover else compressArtwork(cover) ?: return@launch
                    putArtwork(file.fsId, ready)
                    runCatching {
                        artworkFile(file.fsId).parentFile?.mkdirs()
                        artworkFile(file.fsId).writeBytes(ready)
                    }
                }
            } finally {
                onDemandInFlight.remove(file.fsId)
            }
        }
    }
    private val onDemandInFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    fun stopArtworkPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /** 视频开始播放时调用：停掉音频、清空队列，迷你播放器立即消失。 */
    fun stopForVideo() {
        generation++
        windowJob?.cancel()
        pendingStep = 0
        controller?.run {
            pause()
            stop()
            clearMediaItems()
        }
        allFiles = emptyList()
        urls.clear()
        urlTimes.clear()
        stopArtworkPrefetch()
        _state.value = AudioPlaybackState()
    }

    /** 迷你条滑动移除：停止播放、移除迷你条并结束前台播放服务。 */
    fun dismissAndStop() {
        stopForVideo()
        runCatching {
            appContext.startService(
                Intent(appContext, AudioPlaybackService::class.java)
                    .setAction(AudioPlaybackService.ACTION_STOP_SERVICE),
            )
        }
    }

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        val pending = future ?: error("后台播放器尚未初始化")
        return suspendCancellableCoroutine { continuation ->
            pending.addListener({
                runCatching { pending.get() }
                    .onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(appContext))
        }
    }

    /** 取直链：命中未过期缓存才复用，过期或缺失则联网重取。 */
    private suspend fun resolveUrl(file: PanFile): String {
        val cached = urls[file.fsId]
        val time = urlTimes[file.fsId] ?: 0L
        if (!cached.isNullOrBlank() && System.currentTimeMillis() - time <= URL_TTL_MS) {
            return cached
        }
        val url = withContext(Dispatchers.IO) { App.instance.api.locatedownload(file.path) }
        if (url.isNotBlank()) {
            urls[file.fsId] = url
            urlTimes[file.fsId] = System.currentTimeMillis()
        }
        return url
    }

    private fun metadata(file: PanFile): MediaMetadata = MediaMetadata.Builder()
        .setTitle(file.name)
        .setIsPlayable(true)
        .apply {
            _artwork.value[file.fsId]?.let {
                setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
        .build()

    private fun mediaItem(file: PanFile): MediaItem = MediaItem.Builder()
        .setMediaId(file.fsId.toString())
        .setUri(urls.getValue(file.fsId))
        .setMediaMetadata(metadata(file))
        .build()

    private suspend fun ensureArtwork(file: PanFile, url: String): ByteArray? {
        _artwork.value[file.fsId]?.let { return it }
        val cache = artworkFile(file.fsId)
        if (cache.exists()) {
            val cached = runCatching { cache.readBytes() }.getOrNull() ?: return null
            val ready = if (isArtworkCompressed(cached)) cached else compressArtwork(cached) ?: return null
            if (ready !== cached) runCatching { cache.writeBytes(ready) }
            putArtwork(file.fsId, ready)
            return ready
        }
        val bytes = readArtwork(url) ?: return null
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeBytes(bytes)
        }
        putArtwork(file.fsId, bytes)
        return bytes
    }

    private suspend fun readArtwork(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val raw = try {
            retriever.setDataSource(
                url,
                mapOf(
                    "User-Agent" to PanApi.UA,
                    "Cookie" to App.instance.session.cookie,
                ),
            )
            retriever.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        raw?.let { if (isArtworkCompressed(it)) it else compressArtwork(it) }
    }

    /** 已是 JPEG 且最大边不超过阈值时直接复用，避免重复压缩。 */
    private fun isArtworkCompressed(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0 &&
            maxOf(bounds.outWidth, bounds.outHeight) <= ARTWORK_MAX_SIDE
    }

    /** 内嵌封面可能是几 MB 原图，压到最大边 320px/JPEG80，通常几十 KB。 */
    private fun compressArtwork(raw: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val maxSide = ARTWORK_MAX_SIDE
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = if (w == src.width && h == src.height) src
            else Bitmap.createScaledBitmap(src, w, h, true)
        return runCatching {
            val bos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            bos.toByteArray()
        }.getOrNull().also {
            if (out !== src) out.recycle()
            src.recycle()
        }
    }

    private fun cacheArtwork(id: Long, bytes: ByteArray) {
        val ready = if (isArtworkCompressed(bytes)) bytes else compressArtwork(bytes) ?: return
        putArtwork(id, ready)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = artworkFile(id)
                target.parentFile?.mkdirs()
                target.writeBytes(ready)
            }
        }
    }

    private fun putArtwork(id: Long, bytes: ByteArray) {
        _artwork.value = _artwork.value + (id to bytes)
    }

    private fun artworkFile(id: Long): File = com.panclone.app.data.CacheStore.artworkFile(appContext, id)

    private fun publish() {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L
        if (id != 0L && id != lastPublishedId && lastPublishedId != 0L) grantPrefetch()
        lastPublishedId = id
        _state.value = _state.value.copy(
            connected = true,
            hasMedia = player.mediaItemCount > 0,
            mediaId = id,
            title = player.mediaMetadata.title?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            index = allFiles.indexOfFirst { it.fsId == id }.takeIf { it >= 0 }
                ?: player.currentMediaItemIndex.coerceAtLeast(0),
            count = allFiles.size.coerceAtLeast(player.mediaItemCount),
        )
    }
}
