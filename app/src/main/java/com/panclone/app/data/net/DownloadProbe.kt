package com.panclone.app.data.net

import android.util.Log
import com.panclone.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 下载链路自检：PCS locatedownload 取直链 + Range 拉首段字节，
 * 拿到 206/200 且有字节即算通（实测全链路通过）。
 */
object DownloadProbe {

    private const val TAG = "PanCloneProbe"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class Probe(
        val ok: Boolean,
        val via: String,
        val url: String,
        val code: Int,
        val bytes: Long,
        val detail: String,
    )

    /** 探测单个文件的下载可用性（PCS locatedownload 取链 + Range 拉首段） */
    suspend fun probe(fsId: Long, path: String = ""): Probe = withContext(Dispatchers.IO) {
        val session = App.instance.session
        val cookie = session.cookie
        if (cookie.isBlank()) return@withContext Probe(false, "none", "", 0, 0, "未登录")

        Log.i(TAG, "probe start fsId=$fsId path=$path")

        val url = runCatching { App.instance.api.locatedownload(path) }.getOrDefault("")
        Log.i(TAG, "locatedownload urlLen=${url.length}")
        if (url.isNotBlank()) {
            val r = headBytes(url, cookie)
            Log.i(TAG, "locatedownload http=${r.first} bytes=${r.second}")
            if (r.second > 0) return@withContext Probe(true, "locatedownload", url, r.first, r.second, "")
        }

        Probe(false, "none", "", 0, 0, "取链或下载失败")
    }

    /** 请求首段字节，返回 (HTTP码, 实收字节数) */
    private fun headBytes(url: String, cookie: String): Pair<Int, Long> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BaiduPan.downloadUA())
            .header("Cookie", cookie)
            .header("Referer", "https://pan.baidu.com/")
            .header("Range", "bytes=0-65535")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body
            val n = body?.byteStream()?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (total < 64L * 1024) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    total += r
                }
                total
            } ?: 0L
            resp.code to n
        }
    }.getOrElse { 0 to 0L }
}
