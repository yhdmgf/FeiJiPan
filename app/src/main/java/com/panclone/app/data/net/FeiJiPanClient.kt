package com.panclone.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 小飞机网盘（feijipan.com）核心网络层。
 *
 * 严格对齐权威参考实现的协议：
 *   - 所有业务参数拼在 query string（不是 body），固定携带 uuid / devType=6 / devCode / devModel=chrome /
 *     devVersion=131 / appVersion= / timestamp=encrypt(秒) / appToken(登录时) / extra=2（除 /ws/file/redirect 外）。
 *   - AES-128-ECB-PKCS7，key = dingHao-disk-app，密文输出小写 hex。
 *   - 分享文件直链返回的是 AES 加密的 downloadUrl，需要解密；个人文件 redirect 返回明文。
 *   - 复用 Cookie jar 保持跨请求 cookie（域名 feijipan.com / www.feijipan.com / api.feijipan.com）。
 */
class FeiJiPanClient {

    /** 跨请求复用的内存 Cookie jar */
    private val cookieJar = MemoryCookieJar()

    /** 缓存复用的设备标识 uuid（参考实现从 session.meta 缓存一次，避免每次现取多一往返且与会话不一致） */
    @Volatile
    private var cachedUuid: String? = null

    /** 不自动跟随重定向，便于读取 302 的 Location 与 409 的访问验证响应 */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------- 公开接口 ----------

    /** 获取设备标识 uuid，正则校验通过才返回；优先复用缓存的 uuid */
    suspend fun getUuid(): String = withContext(Dispatchers.IO) {
        cachedUuid?.let { return@withContext it }
        val url = buildUrl("/ws/getUuid", "", "")
        val data = checked(request("GET", url))
        val value = data.optString("uuid", "")
        if (!UUID_PATTERN.matches(value)) throw FeiJiPanException("小飞机未返回有效设备标识")
        cachedUuid = value
        value
    }

    /** 供上层在登录/换账号时重置缓存的 uuid（不同 cookie 会话 uuid 可能不同） */
    fun resetUuid() {
        cachedUuid = null
    }

    /** 分享根目录列表（用 /ws/recommend/list） */
    suspend fun listShareRoot(shareId: String, code: String, page: Int): ShareListData =
        withContext(Dispatchers.IO) {
            val uuid = getUuid()
            val url = buildUrl(
                "/ws/recommend/list", uuid, "",
                extra = linkedMapOf(
                    "shareId" to shareId,
                    "code" to code,
                    "offset" to page.toString(),
                    "limit" to "60",
                    "type" to "0",
                ),
            )
            parseShareList(checked(request("POST", url)))
        }

    /** 分享子目录列表（用 /ws/share/list） */
    suspend fun listShareFolder(shareId: String, code: String, folderId: String, page: Int): ShareListData =
        withContext(Dispatchers.IO) {
            val uuid = getUuid()
            val url = buildUrl(
                "/ws/share/list", uuid, "",
                extra = linkedMapOf(
                    "shareId" to shareId,
                    "code" to code,
                    "offset" to page.toString(),
                    "limit" to "60",
                    "folderId" to fileId(folderId),
                ),
            )
            parseShareList(checked(request("POST", url)))
        }

    /**
     * 分享文件取直链（/ws/file/download），返回的 downloadUrl 是 AES 加密的，需解密。
     * 分享解析不登录也能取：此时 token 传空、userId 传空字符串。
     */
    suspend fun resolveShareFileUrl(
        shareId: String,
        code: String,
        fileId: String,
        userId: String,
        token: String,
    ): String = withContext(Dispatchers.IO) {
        val uuid = getUuid()
        val rawId = fileId(fileId)
        val stamp = nowSeconds()
        val url = buildUrl(
            "/ws/file/download", uuid, token,
            timestamp = stamp,
            extra = linkedMapOf(
                "shareId" to shareId,
                "code" to code,
                "enable" to "1",
                "downloadId" to encrypt("$rawId|$userId"),
                "auth" to encrypt("$rawId|$stamp"),
            ),
        )
        val data = checked(request("GET", url))
        decryptDownloadUrl(data.optString("downloadUrl", ""))
    }

    /** 个人网盘文件列表（登录后，/app/record/file/list） */
    suspend fun listPersonal(folderId: String, page: Int, token: String): ShareListData =
        withContext(Dispatchers.IO) {
            val uuid = getUuid()
            val url = buildUrl(
                "/app/record/file/list", uuid, token,
                extra = linkedMapOf(
                    "offset" to page.toString(),
                    "limit" to "60",
                    "folderId" to fileId(folderId),
                    "type" to "0",
                ),
            )
            parseShareList(checked(request("POST", url)))
        }

    /**
     * 个人文件取直链（登录后，/ws/file/redirect）。
     * 返回 302 的 Location 或 JSON 的 url 字段（明文，不需要解密），并做相对 URL resolve。
     */
    suspend fun resolvePersonalFileUrl(fileId: String, userId: String, token: String): String =
        withContext(Dispatchers.IO) {
            val uuid = getUuid()
            val rawId = fileId(fileId)
            val stamp = nowSeconds()
            val url = buildUrl(
                "/ws/file/redirect", uuid, token,
                timestamp = stamp,
                extra = linkedMapOf(
                    "enable" to "1",
                    "downloadId" to encrypt("$rawId|$userId"),
                    "auth" to encrypt("$rawId|$stamp"),
                ),
            )
            val response = request("GET", url)
            var location = response.header("location", "") ?: ""
            if (response.isSuccessful && location.isEmpty()) {
                val data = JSONObject(response.body?.string() ?: "{}")
                location = data.optString("url", "").ifEmpty {
                    data.optJSONObject("data")?.optString("url", "") ?: ""
                }
            }
            if (location.isEmpty()) throw FeiJiPanException("小飞机未返回下载地址，请检查文件权限")
            resolveUrl(location)
        }

    /** 查询当前登录账号的用户 id（登录后，/user/account/map，取 data.map.userId，纯数字字符串） */
    suspend fun getUserId(token: String): String =
        withContext(Dispatchers.IO) {
            val uuid = getUuid()
            val url = buildUrl("/user/account/map", uuid, token)
            val data = checked(request("GET", url))
            val userId = data.optJSONObject("map")?.optString("userId", "").orEmpty()
            if (userId.isBlank() || !userId.toCharArray().all { it.isDigit() }) {
                throw FeiJiPanException("小飞机未返回有效用户 id，请重新登录")
            }
            userId
        }

    // ---------- 内部：请求 / 校验 ----------

    private fun request(method: String, url: String): Response {
        val parsed = url.toHttpUrlOrNull() ?: throw FeiJiPanException("小飞机请求地址无效")
        // 409 访问验证最多重试一次
        for (attempt in 0 until 2) {
            val builder = Request.Builder().url(parsed)
            for ((k, v) in HEADERS) builder.header(k, v)
            if (method == "POST") {
                val body = "".toRequestBody("application/json; charset=utf-8".toMediaType())
                builder.post(body)
            } else {
                builder.get()
            }
            val response = http.newCall(builder.build()).execute()
            val contentType = response.header("content-type", "") ?: ""
            if (response.code == 409 && contentType.contains("text/html")) {
                response.close()
                if (attempt == 0) continue
                throw FeiJiPanException("小飞机的访问验证未通过，请稍后重试或使用网页登录")
            }
            return response
        }
        throw FeiJiPanException("小飞机请求失败，请稍后重试")
    }

    /** 统一响应校验：401 / code==-2 登录过期；2xx 且 code==200 才成功 */
    private fun checked(response: Response): JSONObject {
        response.use { resp ->
            if (resp.code == 401) throw FeiJiPanException("小飞机登录已过期，请重新登录")
            val body = resp.body?.string() ?: "{}"
            val data = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw FeiJiPanException("小飞机请求失败（${resp.code}），请检查登录信息或稍后重试")
            }
            if (data.optInt("code", 0) == -2) throw FeiJiPanException("小飞机登录已过期，请重新登录")
            val success = resp.isSuccessful && data.optInt("code", 0) == 200
            if (!success) {
                val code = data.optString("code", "").ifEmpty { resp.code.toString() }
                throw FeiJiPanException("小飞机请求失败（$code），请检查登录信息或稍后重试")
            }
            return data
        }
    }

    /** 解析分享/个人列表响应，校验提取码与失效状态，展平嵌套 fileList */
    private fun parseShareList(data: JSONObject): ShareListData {
        val status = data.optInt("status", Int.MIN_VALUE)
        if (status == 1 || status == 2) throw FeiJiPanException("小飞机分享需要正确的提取码，请填写后重试")
        if (status == -1) throw FeiJiPanException("小飞机分享已失效或暂不可用")
        val top = data.optJSONArray("list") ?: throw FeiJiPanException("小飞机分享列表格式异常")
        val totalPage = data.optInt("totalPage", 0)
        val files = ArrayList<FeiJiPanFile>()
        for (i in 0 until top.length()) {
            val row = top.optJSONObject(i) ?: continue
            // 行可能嵌套 fileList，需要展平
            val nested = row.optJSONArray("fileList")
            if (nested != null) {
                for (j in 0 until nested.length()) {
                    val child = nested.optJSONObject(j) ?: continue
                    files.add(toFile(child))
                }
            } else {
                files.add(toFile(row))
            }
        }
        return ShareListData(files, totalPage)
    }

    /** 列表行转 FeiJiPanFile，fileSize 单位 KB 需 ×1024 转字节 */
    private fun toFile(row: JSONObject): FeiJiPanFile {
        val type = row.optInt("fileType", 0)
        if (type != 1 && type != 2) throw FeiJiPanException("小飞机返回了不支持的文件类型")
        val directory = type == 2
        val rawId = fileId(row.optString(if (directory) "folderId" else "fileId", ""))
        val name = row.optString(if (directory) "folderName" else "fileName", "")
        if (name.isBlank()) throw FeiJiPanException("小飞机未返回文件名")
        val sizeKb = row.optString("fileSize", "0").toDoubleOrNull() ?: 0.0
        val modifiedAt = row.optString("updTime", "").ifEmpty { row.optString("addTime", "") }
        return FeiJiPanFile(
            id = rawId,
            name = name,
            isDirectory = directory,
            sizeBytes = if (directory) 0L else (sizeKb * 1024).toLong(),
            modifiedAt = modifiedAt,
        )
    }

    // ---------- 内部：URL 构造 ----------

    /** 构造请求 URL：所有参数拼在 query string 上 */
    private fun buildUrl(
        path: String,
        uuid: String,
        token: String,
        timestamp: Long = nowSeconds(),
        extra: Map<String, String> = emptyMap(),
    ): String {
        val values = linkedMapOf<String, String>(
            "uuid" to uuid,
            "devType" to "6",
            "devCode" to uuid,
            "devModel" to "chrome",
            "devVersion" to "131",
            "appVersion" to "",
            "timestamp" to encrypt(timestamp.toString()),
        )
        if (token.isNotEmpty()) values["appToken"] = token
        if (path != "/ws/file/redirect") values["extra"] = "2"
        values.putAll(extra)
        val query = values.entries.joinToString("&") { (k, v) ->
            val encoded = if (k == "appToken") tokenQueryValue(v) else encodeQueryComponent(v)
            "${encodeQueryComponent(k)}=$encoded"
        }
        return "$API_BASE$path?$query"
    }

    /** 相对 URL resolve 到绝对地址 */
    private fun resolveUrl(location: String): String {
        val base = API_BASE.toHttpUrlOrNull() ?: return location
        return base.resolve(location)?.toString() ?: location
    }

    // ---------- 伴生：常量与加解密 ----------

    companion object {
        const val API_BASE = "https://api.feijipan.com"
        const val WEB_BASE = "https://www.feijipan.com"

        /** AES-128 密钥（UTF-8，16 字节） */
        private const val AES_KEY = "dingHao-disk-app"

        /** 标准 Chrome 桌面 UA */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 固定请求头 */
        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Origin" to WEB_BASE,
            "Referer" to "$WEB_BASE/",
            "Accept-Language" to "zh-CN",
        )

        private val HEX_PATTERN = Regex("^[0-9a-fA-F]+$")
        private val UUID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

        private fun cipher(mode: Int): Cipher =
            Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                init(mode, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
            }

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        /** query 编码，空格用 %20 而非 + */
        private fun encodeQueryComponent(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** appToken 编码特殊处理：冒号不编码 */
        private fun tokenQueryValue(value: String): String =
            encodeQueryComponent(value).replace("%3A", ":")

        /** 校验并清洗文件标识（去掉前缀 d: / f:） */
        private fun fileId(value: String): String = value.replaceFirst(Regex("^[df]:"), "")

        /** AES-128-ECB-PKCS7 加密后转小写 hex */
        @JvmStatic
        fun encrypt(value: String): String {
            val bytes = cipher(Cipher.ENCRYPT_MODE)
                .doFinal(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * 解密分享文件返回的加密下载地址。
         * 校验长度 ≤16384、长度 %32==0、纯 hex，再 AES 解密得到明文 URL；失败抛异常。
         */
        @JvmStatic
        fun decryptDownloadUrl(value: String): String {
            if (!(value.length <= 16384 && value.length % 32 == 0 && HEX_PATTERN.matches(value))) {
                throw FeiJiPanException("小飞机下载地址格式异常")
            }
            return try {
                val bytes = ByteArray(value.length / 2)
                for (i in bytes.indices) {
                    bytes[i] = ((Character.digit(value[i * 2], 16) shl 4)
                        + Character.digit(value[i * 2 + 1], 16)).toByte()
                }
                val plain = cipher(Cipher.DECRYPT_MODE).doFinal(bytes)
                String(plain, Charsets.UTF_8)
            } catch (e: Exception) {
                throw FeiJiPanException("小飞机下载地址无法解码，请重新获取")
            }
        }
    }
}

/** 小飞机网盘数据 */
data class FeiJiPanFile(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: String,
)

/** 列表结果 */
data class ShareListData(
    val files: List<FeiJiPanFile>,
    val totalPage: Int,
)

/** 小飞机业务异常 */
class FeiJiPanException(message: String) : RuntimeException(message)

/** 内存 Cookie jar，复用跨请求 cookie 并按域名匹配（feijipan.com 及其子域） */
private class MemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val merged = LinkedHashMap<String, Cookie>()
        store[host]?.forEach { merged[it.name] = it }
        cookies.forEach { merged[it.name] = it }
        store[host] = merged.values.toList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val result = ArrayList<Cookie>()
        store.forEach { (domain, cookies) ->
            if (host == domain || host.endsWith(".$domain")) {
                cookies.forEach { if (!it.expiresAt.let { e -> e != 0L && e < System.currentTimeMillis() }) result.add(it) }
            }
        }
        return result
    }
}
