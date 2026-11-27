package com.panclone.app.data.net

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度网盘取链模块。
 *
 * 协议鉴权封装在 libpansec.so（native，动态注册），Java 层只负责：
 *   1. 通用 HTTP 桥 bridgeHttp()（native 回调，纯转发）
 *   2. JSON 适配
 *   3. 协程封装
 *
 * 返回的直链有时效（约数小时），下载遇到 403/404 时用 nativeRefresh 重新取链。
 */
object BaiduPan {

    /** 与官方抓包一致的下载 UA */
    fun downloadUA(): String =
        "netdisk;P2SP;2.2.91.74;netdisk;12.1.3;" + Build.MODEL +
            ";android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"

    /** 稳定的设备标识，native 侧用于签名 */
    val deviceId: String by lazy {
        val prefs = com.panclone.app.App.instance.getSharedPreferences("device", 0)
        val existing = prefs.getString("devuid", null)
        if (existing != null) existing else {
            val v = UUID.randomUUID().toString().replace("-", "").substring(0, 32).uppercase()
            prefs.edit().putString("devuid", v).apply()
            v
        }
    }

    external fun nativeResolve(link: String, key: String, pwd: String, pdir: String, cookie: String, devuid: String): String
    external fun nativeDownloadUrl(pack: String, cookie: String, devuid: String, ua: String): String
    external fun nativeRefresh(path: String, md5: String, fid: String, dlink: String, cookie: String, devuid: String, ua: String): String

    @Volatile
    private var nativeReady = false

    init {
        nativeReady = runCatching { System.loadLibrary("pansec"); true }.getOrDefault(false)
    }

    val isReady: Boolean get() = nativeReady

    /** native 回调：通用 HTTP 转发，不含业务逻辑 */
    @JvmStatic
    fun bridgeHttp(method: String, url: String, headers: String, body: ByteArray?): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("Accept-Encoding", "identity")
            for (line in headers.split("\n")) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty()) setRequestProperty(k, v)
                }
            }
        }
        return try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val s = if (code in 200..299) conn.inputStream else conn.errorStream
            s?.use { it.readBytes() } ?: ByteArray(0)
        } catch (t: Throwable) {
            ByteArray(0)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 取下载直链。pack 为 native 返回的资源标识 */
    suspend fun downloadUrl(pack: String, cookie: String): Dlink = withContext(Dispatchers.IO) {
        if (!nativeReady) return@withContext Dlink("", error = "native 库加载失败")
        if (!cookie.contains("BDUSS")) return@withContext Dlink("", error = "未登录百度网盘")
        runCatching {
            val js = nativeDownloadUrl(pack, cookie, deviceId, downloadUA())
            val o = JSONObject(js)
            val err = o.optString("err", "")
            if (err.isNotEmpty()) return@runCatching Dlink("", error = err)
            Dlink(
                url = o.optString("url", ""),
                refreshPath = o.optString("refreshPath", ""),
                md5 = o.optString("md5", ""),
                fid = o.optString("fid", ""),
                dlink = o.optString("dlink", ""),
            )
        }.getOrElse { Dlink("", error = "取链失败: ${it.message}") }
    }

    /** 直链失效后刷新 */
    suspend fun refresh(d: Dlink, cookie: String): String = withContext(Dispatchers.IO) {
        if (!nativeReady) return@withContext ""
        runCatching {
            val js = nativeRefresh(d.refreshPath, d.md5, d.fid, d.dlink, cookie, deviceId, downloadUA())
            JSONObject(js).optString("url", "")
        }.getOrDefault("")
    }

    data class Dlink(
        val url: String,
        val refreshPath: String = "",
        val md5: String = "",
        val fid: String = "",
        val dlink: String = "",
        val error: String = "",
    ) {
        val ok: Boolean get() = url.isNotBlank()
    }
}
