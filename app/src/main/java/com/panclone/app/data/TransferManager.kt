package com.panclone.app.data

import android.content.Context
import com.panclone.app.data.model.TransferKind
import com.panclone.app.data.model.TransferState
import com.panclone.app.data.model.TransferTask
import com.panclone.app.data.store.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 传输任务管理：多线程 Range 分段下载 + 断点续传；上传走 web 三步链（precreate → superfile2 → create）。
 * 任务持久化到 filesDir/transfers.json。
 */
class TransferManager(
    private val context: Context,
    private val session: SessionStore,
    private val threads: () -> Int,
    private val downloadClient: OkHttpClient,
    private val resolveUrl: suspend (TransferTask) -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** 上传完成计数：文件页订阅它，每完成一个上传就刷新当前目录 */
    private val _uploadsChanged = MutableStateFlow(0)
    val uploadsChanged: StateFlow<Int> = _uploadsChanged.asStateFlow()
    private val fsIds = ConcurrentHashMap<String, Long>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile = File(context.filesDir, "transfers.json")

    init {
        runCatching {
            if (storeFile.exists()) {
                val list = json.decodeFromString<List<TransferTask>>(storeFile.readText())
                _tasks.value = list.map {
                    if (it.state == TransferState.RUNNING) it.copy(state = TransferState.PAUSED) else it
                }
            }
        }
    }

    private fun persist() = runCatching { storeFile.writeText(json.encodeToString(_tasks.value)) }.let { }

    private fun update(id: String, block: (TransferTask) -> TransferTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) block(it) else it }
    }

    fun registerFsId(path: String, fsId: Long) { fsIds[path] = fsId }

    fun addDownload(name: String, remotePath: String, fsId: Long, size: Long, localDir: File, thumb: String = ""): TransferTask {
        val dir = if (localDir.exists() || localDir.mkdirs()) localDir else File(context.filesDir, "downloads").apply { mkdirs() }
        val target = uniqueFile(dir, name)
        fsIds[remotePath] = fsId
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.DOWNLOAD,
            name = name,
            remotePath = remotePath,
            localPath = target.absolutePath,
            size = size,
            thumb = thumb,
        )
        _tasks.value = _tasks.value + task
        persist()
        return task
    }

    fun addDirectDownload(
        name: String,
        url: String,
        size: Long,
        localDir: File,
        thumb: String = "",
    ): TransferTask {
        val dir = if (localDir.exists() || localDir.mkdirs()) localDir
        else File(context.filesDir, "downloads").apply { mkdirs() }
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.DOWNLOAD,
            name = name,
            remotePath = "share://$name",
            localPath = uniqueFile(dir, name).absolutePath,
            size = size,
            thumb = thumb,
            directUrl = url,
        )
        _tasks.value = _tasks.value + task
        persist()
        return task
    }

    fun addUpload(localFile: File, remoteDir: String): TransferTask {
        val remote = if (remoteDir.endsWith("/")) remoteDir else "$remoteDir/"
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.UPLOAD,
            name = localFile.name,
            remotePath = remote + localFile.name,
            localPath = localFile.absolutePath,
            size = localFile.length(),
        )
        _tasks.value = _tasks.value + task
        persist()
        return task
    }

    /** 第三方分享上传：只记录 URI，上传时原位读取。 */
    fun addUploadUri(uri: android.net.Uri, name: String, size: Long, remoteDir: String): TransferTask {
        val remote = if (remoteDir.endsWith("/")) remoteDir else "$remoteDir/"
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.UPLOAD,
            name = name,
            remotePath = remote + name,
            localPath = "",
            size = size,
            sourceUri = uri.toString(),
        )
        _tasks.value = _tasks.value + task
        persist()
        return task
    }

    fun start(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (jobs[taskId]?.isActive == true) return
        update(taskId) { it.copy(state = TransferState.RUNNING, error = "") }
        persist()
        jobs[taskId] = scope.launch {
            try {
                when (task.kind) {
                    TransferKind.DOWNLOAD -> download(taskId, task)
                    TransferKind.UPLOAD -> upload(taskId, task)
                }
                if (task.kind == TransferKind.UPLOAD) _uploadsChanged.value += 1
                update(taskId) { it.copy(state = TransferState.DONE, doneAt = System.currentTimeMillis() / 1000) }
            } catch (e: Exception) {
                update(taskId) {
                    if (it.state == TransferState.PAUSED) it else it.copy(state = TransferState.FAILED, error = e.message ?: "失败")
                }
            }
            persist()
        }
    }

    fun pause(taskId: String) {
        update(taskId) { it.copy(state = TransferState.PAUSED) }
        jobs.remove(taskId)?.cancel()
        persist()
    }

    fun resume(taskId: String) = start(taskId)

    fun remove(taskId: String) {
        jobs.remove(taskId)?.cancel()
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.state == TransferState.DONE || it.state == TransferState.FAILED }
        persist()
    }

    /**
     * 下载：PCS locatedownload 取直链（实测任意大小文件可用）+ 分块并发
     * （256KB/块，官方同款；大跨度 Range 会被 CDN 403）。
     * 块失败两段重试（等 900ms → 刷新直链）；失败任务置 FAILED，不崩 App；断点续传。
     */
    private suspend fun download(taskId: String, task: TransferTask) {
        val total = task.size.coerceAtLeast(1)
        val target = File(task.localPath)
        val part = File(task.localPath + ".part")

        var startPos = task.done.coerceIn(0L, total)
        if (startPos <= 0L || !part.exists()) {
            startPos = 0
            part.delete()
        }
        RandomAccessFile(part, "rw").use { it.setLength(total) }
        update(taskId) { it.copy(done = startPos) }

        // 小飞机直链：分享文件需解密（client 内处理），个人文件是明文；
        // 每次取链可能过期，断点续传/重试时重新 resolve。
        val url = task.directUrl.ifBlank { resolveUrl(task) }
        if (url.isBlank()) throw IllegalStateException("获取直链失败，可能登录已失效")
        downloadChunked(taskId, task, url, part, startPos, total)

        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        update(taskId) { it.copy(done = total) }
        persist()
    }

    /** <295MB：分块并发（窗口=线程数，块大小随并发自适应，限制在途内存） */
    private suspend fun downloadChunked(taskId: String, task: TransferTask, url0: String, part: File, startPos: Long, total: Long) {
        val urlRef = java.util.concurrent.atomic.AtomicReference(url0)
        val threads = threads().coerceIn(1, 1280)
        // 大文件（≥64MB）实测：CDN 对多线程限流极严（>8 并发主动断连 IncompleteRead），
        // 强行高并发会全块失败；降级到 PanNative 验证过的安全参数（窗口 8 × 256KB）。
        // 小文件不限流，用户设置的高并发正常生效。
        val large = total >= 64L * 1024 * 1024
        val threads2 = if (large) minOf(threads, 8) else threads
        val chunk = when {
            large -> 256L * 1024
            threads2 <= 64 -> 256L * 1024
            threads2 <= 256 -> 128L * 1024
            threads2 <= 512 -> 64L * 1024
            else -> 32L * 1024
        }
        val counter = AtomicLong(startPos)

        suspend fun fetch(off: Long, span: Long, u: String): ChunkRes = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$off-${off + span - 1}")
                    .build()
                downloadClient.newCall(req).execute().use { resp ->
                    if (resp.code == 416) return@withContext ChunkRes(off, ByteArray(0), null)
                    if (!resp.isSuccessful) return@withContext ChunkRes(off, null, "HTTP ${resp.code}")
                    val buf = ByteArray(span.toInt())
                    var got = 0
                    resp.body?.byteStream()?.use { ins ->
                        while (got < span) {
                            val n = ins.read(buf, got, (span - got).toInt())
                            if (n <= 0) break
                            got += n
                        }
                    }
                    if (got < span) ChunkRes(off, null, "连接提前结束")
                    else ChunkRes(off, buf.copyOf(got), null)
                }
            }.getOrElse { ChunkRes(off, null, it.message ?: "网络错误") }
        }

        coroutineScope {
            // 生产者：持续把块任务丢进窗口队列（容量=并发数）
            val queue = Channel<Deferred<ChunkRes>>(capacity = threads2)
            launch {
                var next = startPos
                try {
                    while (next < total) {
                        val off = next
                        val span = minOf(chunk, total - off)
                        next += span
                        val u = urlRef.get()
                        queue.send(async { fetch(off, span, u) })
                    }
                } finally {
                    queue.close()
                }
            }
            // 消费者：按序写盘，断点即当前写盘位置
            RandomAccessFile(part, "rw").use { out ->
                var lastPersist = 0L
                for (deferred in queue) {
                    var res = runCatching { deferred.await() }
                        .getOrElse { ChunkRes(-1L, null, "取块失败") }
                    if (res.data == null && res.off >= 0) {
                        val span = minOf(chunk, total - res.off)
                        delay(900)
                        res = fetch(res.off, span, urlRef.get())
                        if (res.data == null) {
                            // 直链可能过期，重新取链后重试（保留断点）
                            val fresh = if (task.directUrl.isBlank()) resolveUrl(task) else ""
                            if (fresh.isNotBlank()) urlRef.set(fresh)
                            res = fetch(res.off, span, urlRef.get())
                        }
                    }
                    if (res.data == null || res.off < 0) {
                        throw IllegalStateException("下载失败（${res.err ?: "未知"}），请暂停后重试以重新取链")
                    }
                    out.seek(res.off)
                    out.write(res.data)
                    val doneNow = counter.addAndGet(res.data.size.toLong())
                    update(taskId) { it.copy(done = doneNow) }
                    val now = System.currentTimeMillis()
                    if (now - lastPersist > 1000) {
                        persist()
                        lastPersist = now
                    }
                }
            }
        }
    }

    private class ChunkRes(val off: Long, val data: ByteArray?, val err: String?)

    companion object {
        /** 下载分块请求用桌面 UA（小飞机直链校验 UA） */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /** 上传：小飞机网盘不开放上传接口，任务置为失败并提示。 */
    private suspend fun upload(taskId: String, task: TransferTask) {
        throw IllegalStateException("小飞机网盘暂不支持上传")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
            i++
        }
        return f
    }
}
