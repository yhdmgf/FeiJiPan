package com.panclone.app.data.net

import com.panclone.app.data.model.CategoryListResponse
import com.panclone.app.data.model.FileListResponse
import com.panclone.app.data.model.QuotaResponse
import com.panclone.app.data.model.RecycleListResponse
import com.panclone.app.data.model.ShareRecordResponse
import com.panclone.app.data.model.ShareResponse
import com.panclone.app.data.model.SimpleResponse
import com.panclone.app.data.model.TemplateVariableResponse
import com.panclone.app.data.model.TemplateVariables
import com.panclone.app.data.model.UserInfoResponse
import com.panclone.app.data.model.VerifyActionResponse
import com.panclone.app.data.model.VerifyApiResponse
import com.panclone.app.data.model.VerifyPublicKey
import com.panclone.app.data.model.VipQueryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 百度网盘 web 接口客户端（BDUSS Cookie 鉴权）。
 *
 * 写操作（create / filemanager）必须带 BDUSS，且 filelist 是 JSON 数组字符串；
 * 所有请求统一带浏览器 UA + Referer，否则服务端会返回 errno=2 参数错误。
 */
class PanApi(private val cookieProvider: () -> String) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val downloadClient: OkHttpClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        // 128 并发分块下载需要：OkHttp 默认每主机仅 5 并发会排队
        .dispatcher(Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 256
        })
        .connectionPool(ConnectionPool(128, 5, TimeUnit.MINUTES))
        .build()

    companion object {
        const val UA =
            "netdisk;P2SP;3.0.0;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"
        /** PCS 接口（locatedownload / superfile2 / precreate / create）实测可用的客户端 UA */
        const val UA_PCS =
            "netdisk;P2SP;2.2.91.74;netdisk;12.1.3;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"
        /** 浏览器 UA（gettemplatevariable 用） */
        const val UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        const val BLOCK_SIZE = 4L * 1024 * 1024
        private const val BASE = "https://pan.baidu.com"
        private const val APP_ID = "250528"
        private const val PCS_FILE = "https://d.pcs.baidu.com/rest/2.0/pcs/file"
        private const val PCS_UPLOAD = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2"
    }

    private fun auth(): String = cookieProvider()

    private fun reqBuilder(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Cookie", auth())
        .header("Referer", "$BASE/disk/home")
        .header("Accept", "application/json, text/plain, */*")

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = "$BASE$path".toHttpUrl().newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            runCatching {
                client.newCall(reqBuilder(builder.build().toString()).get().build())
                    .execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
        }

    private suspend fun post(path: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
            }.build()
            runCatching {
                client.newCall(reqBuilder("$BASE$path").post(body).build())
                    .execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
        }

    private inline fun <reified T> parse(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()

    fun isAuthError(errno: Int) = errno == -6 || errno == 111 || errno == 31045

    /** errno -> 中文说明 */
    fun errnoMessage(errno: Int, fallback: String = ""): String = when (errno) {
        0 -> ""
        -1 -> "服务端异常，请稍后再试"
        -3 -> "文件不存在"
        -6 -> "登录已失效，请重新登录"
        -7 -> "文件名非法"
        -8 -> "文件已存在"
        -9 -> "目录不存在"
        -10 -> "空间不足"
        -11 -> "文件名过长"
        -12 -> "文件已存在"
        -15 -> "路径非法"
        -19 -> "容量不足"
        -31 -> "超出单次操作数量限制"
        2 -> "参数错误"
        111 -> "登录已失效"
        31045 -> "user not exists"
        31066 -> "文件不存在"
        132 -> "需要短信验证身份"
        31180 -> "文件名包含非法字符"
        31181 -> "文件已存在"
        8001 -> "操作受限：文件可能被占用或正在传输，请稍后重试"
        else -> fallback.ifBlank { "操作失败（$errno）" }
    }

    // ---------------- 读 ----------------

    suspend fun list(
        dir: String,
        page: Int = 1,
        num: Int = 200,
        order: String = "name",
        desc: Int = 0,
    ): FileListResponse {
        val text = get(
            "/api/list",
            mapOf(
                "dir" to dir,
                "order" to order,
                "desc" to desc.toString(),
                "page" to page.toString(),
                "num" to num.toString(),
                "showempty" to "0",
                "web" to "1",
                "clienttype" to "0",
                "channel" to "chunlei",
                "app_id" to APP_ID,
            ),
        )
        if (text.isBlank()) android.util.Log.w("PanApi", "list blank for dir=$dir")
        else android.util.Log.i("PanApi", "list dir=$dir: " + text.take(200))
        return parse<FileListResponse>(text)
            ?: FileListResponse(errno = -1, showMsg = if (text.isBlank()) "网络请求失败" else "响应解析失败")
    }

    suspend fun quota(): QuotaResponse {
        val text = get(
            "/api/quota",
            mapOf("checkfree" to "1", "checkexpire" to "1", "clienttype" to "0", "app_id" to APP_ID),
        )
        return parse<QuotaResponse>(text) ?: QuotaResponse(errno = -1, showMsg = "响应解析失败")
    }

    /**
     * 用户信息（网页版抓包版）：/disk/main 的 SSR HTML 内嵌 window.locals，
     * 从中提取 username / photo_url（/api/user/getinfo 已废弃，返回 params error）。
     * 需完整 cookie（BDUSS+STOKEN+PANPSC）+ 浏览器 UA。
     */
    suspend fun userInfo(): UserInfoResponse = withContext(Dispatchers.IO) {
        val html = runCatching {
            client.newCall(
                Request.Builder()
                    .url("$BASE/disk/main")
                    .header("User-Agent", UA_WEB)
                    .header("Cookie", auth())
                    .get()
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        val username = Regex("\"username\":\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val photo = Regex("\"photo_url\":\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        if (username.isBlank()) {
            android.util.Log.w("PanApi", "userInfo extract fail, html=" + html.take(200))
            return@withContext UserInfoResponse(errno = -1, showMsg = "获取用户信息失败")
        }
        UserInfoResponse(errno = 0, baiduName = username, avatarUrl = photo)
    }

    suspend fun search(key: String, dir: String = "/"): FileListResponse {
        val text = get(
            "/api/search",
            mapOf(
                "key" to key,
                "dir" to dir,
                "recursion" to "1",
                "page" to "1",
                "num" to "100",
                "clienttype" to "0",
                "app_id" to APP_ID,
                "web" to "1",
            ),
        )
        return parse<FileListResponse>(text) ?: FileListResponse(errno = -1, showMsg = "响应解析失败")
    }

    // ---------------- 写 ----------------

    /**
     * 新建文件夹（网页版抓包版）：/api/create?a=commit&bdstoken&web=1，
     * path 必须是「完整的新目录路径」（父目录拼名称），只传父目录会报参数错误。
     */
    suspend fun createFolder(dir: String, name: String): SimpleResponse {
        val full = if (dir.endsWith("/")) dir + name else "$dir/$name"
        val text = postQ(
            "/api/create",
            mapOf(
                "a" to "commit",
                "bdstoken" to bdstoken(),
                "clienttype" to "0",
                "app_id" to APP_ID,
                "web" to "1",
            ),
            mapOf(
                "path" to full,
                "isdir" to "1",
                "block_list" to "[]",
            ),
        )
        val resp = parse<SimpleResponse>(text) ?: SimpleResponse(errno = -1, showMsg = "响应解析失败")
        if (resp.errno != 0) {
            android.util.Log.w("PanApi", "createFolder errno=${resp.errno} full=$full raw=" + text.take(400))
        }
        return resp
    }

    /**
     * 重命名（网页版抓包版）：filelist 项需要 path + newname + isdir。
     */
    suspend fun rename(path: String, newName: String, isDir: Boolean = false): SimpleResponse {
        val body = buildJsonArray {
            add(buildJsonObject {
                put("path", path)
                put("newname", newName)
                put("isdir", if (isDir) 1 else 0)
            })
        }.toString()
        return filemanagerAsync("rename", body, "rename $path -> $newName")
    }

    /** 移动 / 复制（网页版抓包版）：filelist 项需要 path + dest + newname */
    suspend fun moveOrCopy(paths: List<String>, dest: String, copy: Boolean): SimpleResponse {
        val body = buildJsonArray {
            paths.forEach { p ->
                add(buildJsonObject {
                    put("path", p)
                    put("dest", dest)
                    put("newname", p.substringAfterLast('/'))
                })
            }
        }.toString()
        return filemanagerAsync(if (copy) "copy" else "move", body, "${if (copy) "copy" else "move"} -> $dest")
    }

    /**
     * filemanager 异步写操作通用链（delete/rename/copy/move 同构，网页版抓包验证）：
     * async=2 + newVerify=1 + bdstoken（缺任一会 errno=8001 安全验证拦截），
     * 提交返回 taskid → 轮询 /share/taskquery 直到任务结束，task_errno=0 即成功。
     */
    private suspend fun filemanagerAsync(opera: String, filelistJson: String, logTag: String): SimpleResponse {
        val text = postQ(
            "/api/filemanager",
            mapOf(
                "async" to "2",
                "onnest" to "fail",
                "opera" to opera,
                "bdstoken" to bdstoken(),
                "newVerify" to "1",
                "clienttype" to "0",
                "app_id" to APP_ID,
                "web" to "1",
            ),
            mapOf("filelist" to filelistJson),
        )
        val resp = parse<SimpleResponse>(text) ?: SimpleResponse(errno = -1, showMsg = "响应解析失败")
        if (resp.errno != 0) {
            android.util.Log.w("PanApi", "$logTag errno=${resp.errno} raw=" + text.take(500))
            return resp
        }
        val taskid = Regex("\"taskid\":(\\d+)").find(text)?.groupValues?.get(1)
        if (taskid != null) {
            repeat(10) {
                kotlinx.coroutines.delay(700)
                val tq = get(
                    "/share/taskquery",
                    mapOf("taskid" to taskid, "clienttype" to "0", "app_id" to APP_ID, "web" to "1"),
                )
                val st = Regex("\"status\":\"([^\" ]+)\"").find(tq)?.groupValues?.get(1).orEmpty()
                val te = Regex("\"task_errno\":(-?\\d+)").find(tq)?.groupValues?.get(1)?.toInt() ?: -99
                if (st != "running") {
                    return if (te == 0) resp else SimpleResponse(errno = te, showMsg = "任务执行失败")
                }
            }
        }
        return resp
    }

    /** 删除：filelist 是路径字符串数组 */
    suspend fun delete(paths: List<String>): SimpleResponse {
        val body = buildJsonArray { paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }.toString()
        return filemanagerAsync("delete", body, "delete paths=$paths")
    }

    /**
     * 创建分享（网页版抓包版）：端点是 /share/set（无 /api 前缀，/api/share/set 已 404）。
     * fid_list 是 fs_id 数字数组；pwd 4 位；schannel=4 私密链接。
     */
    suspend fun createShare(fsIds: List<Long>, period: Int = 0, pwd: String = ""): ShareResponse {
        val fidList = fsIds.joinToString(",", "[", "]")
        val text = postQ(
            "/share/set",
            mapOf(
                "app_id" to APP_ID,
                "channel" to "chunlei",
                "clienttype" to "0",
                "web" to "1",
                "bdstoken" to bdstoken(),
            ),
            mapOf(
                "period" to period.toString(),
                "pwd" to pwd,
                "schannel" to "4",
                "channel_list" to "[]",
                "eflag_disable" to "true",
                "fid_list" to fidList,
            ),
        )
        val resp = parse<ShareResponse>(text) ?: ShareResponse(errno = -1, showMsg = "响应解析失败")
        if (resp.errno != 0) {
            android.util.Log.w("PanApi", "share/set errno=${resp.errno} raw=" + text.take(400))
        }
        return resp
    }

    /**
     * 编辑分享（网页版抓包版）：/share/pset 带 shareid 即修改有效期/提取码（surl 链接不变）。
     * period：1/7/30/365 天，0=永久；pwd 4 位。
     */
    suspend fun modifyShare(shareId: Long, fsIds: List<Long>, period: Int, pwd: String): ShareResponse {
        val fidList = fsIds.joinToString(",", "[", "]")
        val text = postQ(
            "/share/pset",
            mapOf(
                "channel" to "chunlei",
                "bdstoken" to bdstoken(),
                "clienttype" to "0",
                "app_id" to APP_ID,
                "web" to "1",
            ),
            mapOf(
                "shareid" to shareId.toString(),
                "period" to period.toString(),
                "pwd" to pwd,
                "schannel" to "4",
                "channel_list" to "[]",
                "eflag_disable" to "true",
                "fid_list" to fidList,
            ),
        )
        val resp = parse<ShareResponse>(text) ?: ShareResponse(errno = -1, showMsg = "响应解析失败")
        if (resp.errno != 0) {
            android.util.Log.w("PanApi", "share/pset errno=${resp.errno} raw=" + text.take(400))
        }
        return resp
    }

    /**
     * 会员信息（网页版抓包版）：/rest/2.0/membership/user?method=query
     * current_product_v2=当前会员；previous_product=已过期会员（含 expired_time）。
     */
    /** membership 接口在 HTTP/2 下会返回空响应，强制 HTTP/1.1（实测 curl/urllib 均正常） */
    private val http11Client: OkHttpClient by lazy {
        client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    suspend fun vipQuery(): VipQueryResponse {
        val text = runCatching {
            val u = "$BASE/rest/2.0/membership/user".toHttpUrl().newBuilder().apply {
                addQueryParameter("method", "query")
                addQueryParameter("clienttype", "0")
                addQueryParameter("app_id", APP_ID)
                addQueryParameter("web", "1")
            }.build().toString()
            http11Client.newCall(
                Request.Builder().url(u)
                    .header("User-Agent", UA_WEB)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Cookie", auth())
                    .get()
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        val resp = parse<VipQueryResponse>(text)
        if (resp == null || (resp.current == null && resp.previous == null)) {
            android.util.Log.w("PanApi", "vipQuery fail, resp=" + text.take(300))
        }
        return resp ?: VipQueryResponse()
    }

    suspend fun cancelShare(shareIds: List<Long>): SimpleResponse {
        val text = postQ(
            "/share/cancel",
            mapOf("app_id" to APP_ID, "channel" to "chunlei", "clienttype" to "0", "web" to "1", "bdstoken" to bdstoken()),
            // 网页版抓包确认字段名是 shareid_list（原 shareIdList 是错的）
            mapOf("shareid_list" to shareIds.joinToString(",", "[", "]")),
        )
        return parse<SimpleResponse>(text) ?: SimpleResponse(errno = -1, showMsg = "响应解析失败")
    }

    // ---------------- 音频分类列表（网页版 /api/categorylist） ----------------

    /** 按分类一次取 100 条文件；category=2 即音频。分页用 page 递增。 */
    suspend fun categoryList(category: Int, page: Int = 1, num: Int = 100): CategoryListResponse {
        val text = get(
            "/api/categorylist",
            mapOf(
                "clienttype" to "0",
                "app_id" to APP_ID,
                "web" to "1",
                "channel" to "chunlei",
                "order" to "time",
                "desc" to "1",
                "num" to num.toString(),
                "page" to page.toString(),
                "category" to category.toString(),
            ),
        )
        return parse<CategoryListResponse>(text)
            ?: CategoryListResponse(errno = -1, showMsg = if (text.isBlank()) "网络请求失败" else "响应解析失败")
    }

    // ---------------- 回收站（网页版 /api/recycle/*） ----------------

    suspend fun recycleList(page: Int = 1, num: Int = 100): RecycleListResponse {
        val text = get(
            "/api/recycle/list/",
            mapOf(
                "clienttype" to "0",
                "app_id" to APP_ID,
                "num" to num.toString(),
                "page" to page.toString(),
                "web" to "1",
            ),
        )
        return parse<RecycleListResponse>(text)
            ?: RecycleListResponse(errno = -1, showMsg = "响应解析失败")
    }

    /** 还原回收站文件；fidlist 是 fs_id 数组字符串。返回 errno=132 表示需要风控验证。 */
    suspend fun recycleRestore(fsIds: List<Long>): SimpleResponse = recycleOperate("restore", fsIds)

    /** 彻底删除回收站文件。 */
    suspend fun recycleDelete(fsIds: List<Long>): SimpleResponse = recycleOperate("delete", fsIds)

    /** 清空回收站（无 fidlist）。 */
    suspend fun recycleClear(): SimpleResponse = recycleOperate("clear", emptyList())

    private suspend fun recycleOperate(opera: String, fsIds: List<Long>): SimpleResponse {
        val token = bdstoken()
        val query = mutableMapOf(
            "channel" to "chunlei",
            "bdstoken" to token,
        )
        // 网页版：clear 无 async；delete/restore 带 async=1
        if (opera != "clear") query["async"] = "1"
        val path = when (opera) {
            "restore" -> "/api/recycle/restore"
            "delete" -> "/api/recycle/delete"
            else -> "/api/recycle/clear?async=1"
        }
        val body = if (opera == "clear") emptyMap()
            else mapOf("fidlist" to fsIds.joinToString(",", "[", "]"))
        val text = postQ(path, query, body)
        return parse<SimpleResponse>(text) ?: SimpleResponse(errno = -1, showMsg = "响应解析失败")
    }

    // ---------------- 分享记录（网页版 /share/record） ----------------

    /** 我的分享记录；order=ctime desc=1。 */
    suspend fun shareRecord(page: Int = 1, num: Int = 100): ShareRecordResponse {
        val text = get(
            "/share/record",
            mapOf(
                "channel" to "chunlei",
                "clienttype" to "0",
                "app_id" to APP_ID,
                "num" to num.toString(),
                "page" to page.toString(),
                "web" to "1",
                "order" to "ctime",
                "desc" to "1",
                "is_batch" to "1",
            ),
        )
        return parse<ShareRecordResponse>(text)
            ?: ShareRecordResponse(errno = -1, showMsg = "响应解析失败")
    }

    // ==================== 风控强制验证（errno=132） ====================

    private val PASSPORT = "https://passport.baidu.com"

    /** passport 与 gettemplatevariable 在 HTTP/2 下返回空 body，强制 HTTP/1.1（实测）。
     *  与 vipQuery 的 http11Client 同理：Python/urllib 走 HTTP/1.1 正常，OkHttp 默认 HTTP/2 会空。 */
    private val http11ClientAuth: OkHttpClient by lazy {
        client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    }

    /** 取完整模板变量（bdstoken + token），token 用于 passport 强制验证。
     *  用正则提取（与 bdstoken() 同源）：token 内含 \/ 等转义，整包序列化解析在真机上会失败返回空。 */
    suspend fun templateVariables(): TemplateVariables {
        // 必须与已验证可用的 bdstoken() 用完全相同的请求方式：
        // fields=["bdstoken","token","uk"] + UA_WEB + Cookie + Referer。
        // 之前多加 X-Requested-With/channel/dp-logid 会让该接口返回空 body。
        // 必须在 IO 线程：VerifyDialog 在 LaunchedEffect(主线程) 调本函数，
        // 直接 execute() 会抛 NetworkOnMainThreadException 导致 body 为空。
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val u = "$BASE/api/gettemplatevariable".toHttpUrl().newBuilder().apply {
                    addQueryParameter("fields", "[\"bdstoken\",\"token\",\"uk\"]")
                    addQueryParameter("clienttype", "0")
                    addQueryParameter("app_id", APP_ID)
                    addQueryParameter("web", "1")
                }.build().toString()
                http11ClientAuth.newCall(
                    Request.Builder().url(u)
                        .header("User-Agent", UA_WEB)
                        .header("Cookie", auth())
                        .header("Referer", "$BASE/disk/home")
                        .get().build()
                ).execute().use { resp ->
                    val b = resp.body?.string().orEmpty()
                    android.util.Log.i("PanApi", "gettemplatevariable code=${resp.code} len=${b.length} body=${b.take(300)}")
                    b
                }
            }.getOrElse { e ->
                android.util.Log.w("PanApi", "gettemplatevariable EXCEPTION: ${e.javaClass.simpleName} ${e.message}")
                ""
            }
        }
        val bdstoken = pickString(text, "bdstoken")
        var token = pickString(text, "token")
        if (token.isBlank()) token = mainPageToken()
        val uk = Regex("\"uk\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        android.util.Log.i("PanApi", "templateVariables len=${text.length} bdstoken=${bdstoken.length} token=${token.length} body=${text.take(300)}")
        return TemplateVariables(bdstoken = bdstoken, token = token, uk = uk)
    }

    /** 先从 JSON（含 result 嵌套）取字符串，失败再用正则兜底。 */
    private fun pickString(text: String, key: String): String {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val obj = root["result"]?.jsonObject ?: root
            val v = obj[key]?.jsonPrimitive?.content
            if (!v.isNullOrBlank()) return v
        }
        return extractJsonString(text, key)
    }

    /** 从普通/转义 JSON、JS 对象中取字符串字段。 */
    private fun extractJsonString(text: String, key: String): String {
        val patterns = listOf(
            Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""),
            Regex("\\\\\"$key\\\\\"\\s*:\\s*\\\\\"((?:\\\\.|[^\\\\\"])*)\\\\\""),
            Regex("\\b$key\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""),
        )
        for (pattern in patterns) {
            val raw = pattern.find(text)?.groupValues?.get(1) ?: continue
            val value = unescapeJson(raw)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun unescapeJson(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /** /api/gettemplatevariable 偶发空 token 时，从网页 SSR 的 yunData 兜底。 */
    private suspend fun mainPageToken(): String = withContext(Dispatchers.IO) {
        val html = runCatching {
            client.newCall(
                Request.Builder()
                    .url("$BASE/disk/main")
                    .header("User-Agent", UA_WEB)
                    .header("Cookie", auth())
                    .header("Referer", "$BASE/disk/home")
                    .get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        pickString(html, "token")
    }

    /** passport 通用 jsonp GET（用 OkHttp 抓 {} 内 JSON） */
    private suspend fun passportGet(url: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = url.toHttpUrl().newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            val raw = runCatching {
                http11ClientAuth.newCall(
                    Request.Builder().url(builder.build().toString())
                        .header("User-Agent", UA_WEB)
                        .header("Cookie", auth())
                        .header("Referer", "$BASE/disk/home")
                        .get().build()
                ).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
            android.util.Log.i("PanApi", "passport ${builder.build()} -> ${raw.take(300)}")
            raw
        }

    /** 取可用验证方式。errno=110000 表示成功。 */
    suspend fun verifyGetApi(token: String): VerifyApiResponse {
        val text = passportGet(
            "$PASSPORT/v2/sapi/authwidgetverify",
            mapOf(
                "bankcheck" to "1",
                "action" to "getapi",
                "authtoken" to token,
                "jsonp" to "1",
                "apiver" to "v3",
                "tpl" to "netdisk",
                "subpro" to "netdisk_web",
            ),
        )
        return parse<VerifyApiResponse>(cleanJsonp(text))
            ?: VerifyApiResponse(errno = -1, msg = "获取验证方式失败")
    }

    /** 发送短信验证码到绑定手机（type=mobile）。 */
    suspend fun verifySendSms(token: String, authAction: String = "recyclebin"): VerifyActionResponse {
        val text = passportGet(
            "$PASSPORT/v2/sapi/authwidgetverify",
            mapOf(
                "action" to "send",
                "type" to "mobile",
                "authtoken" to token,
                "jsonp" to "1",
                "apiver" to "v3",
                "tpl" to "netdisk",
                "subpro" to "netdisk_web",
                "authAction" to authAction,
            ),
        )
        return parse<VerifyActionResponse>(cleanJsonp(text))
            ?: VerifyActionResponse(errno = -1, msg = "发送验证码失败")
    }

    /** 取 RSA 公钥（短信验证码需加密提交）。 */
    suspend fun verifyPublicKey(): VerifyPublicKey {
        val text = passportGet("$PASSPORT/v2/getpublickey", mapOf("jsonp" to "1"))
        return parse<VerifyPublicKey>(cleanJsonp(text)) ?: VerifyPublicKey()
    }

    /** 提交短信验证码（vcode 需 RSA 加密）。errno=110000 成功。 */
    /** check 只传 vcode/rsakey，不能传 authAction（网页 _submit_vcode 同样不传，传了会 62004）。 */
    suspend fun verifyCheckSms(token: String, encryptedVcode: String, rsakey: String): VerifyActionResponse {
        val text = passportGet(
            "$PASSPORT/v2/sapi/authwidgetverify",
            mapOf(
                "action" to "check",
                "type" to "mobile",
                "authtoken" to token,
                "vcode" to encryptedVcode,
                "rsakey" to rsakey,
                "jsonp" to "1",
                "apiver" to "v3",
                "tpl" to "netdisk",
                "subpro" to "netdisk_web",
            ),
        )
        return parse<VerifyActionResponse>(cleanJsonp(text))
            ?: VerifyActionResponse(errno = -1, msg = "验证失败")
    }

    /** 验证通过后写入会话（关键步骤），之后重试原操作即可成功。 */
    suspend fun verifyCommitAuth(): SimpleResponse {
        val text = get(
            "/api/report/user",
            mapOf("action" to "sapi_auth", "clienttype" to "0", "app_id" to APP_ID, "web" to "1"),
        )
        return parse<SimpleResponse>(text) ?: SimpleResponse(errno = -1, showMsg = "验证写入失败")
    }

    /** passport 返回单引号 JSONP + JS 表达式（如 '195****' || ''），清洗成标准 JSON。 */
    private fun cleanJsonp(text: String): String {
        var t = text.trim()
        val start = t.indexOf('(')
        val end = t.lastIndexOf(')')
        if (start >= 0 && end > start) t = t.substring(start + 1, end)
        // 'x' || 'y' -> 'x'，避免留下多余引号
        t = Regex("'([^']*)'\\s*\\|\\|\\s*'[^']*'").replace(t, "'$1'")
        t = Regex("\"([^\"]*)\"\\s*\\|\\|\\s*\"[^\"]*\"").replace(t, "\"$1\"")
        t = t.replace('\'', '"').replace("\\/", "/")
        return t
    }

    /** RSA 公钥加密（PKCS1 v1.5，base64 输出），用于短信验证码。 */
    fun rsaEncrypt(plain: String, pubkey: String): String = runCatching {
        val clean = pubkey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val spec = java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(clean, android.util.Base64.DEFAULT))
        val key = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
    }.getOrDefault("")


    // ---------------- 下载直链（PCS locatedownload，实测全链路通过） ----------------

    private fun reqBuilderPcs(url: String) = reqBuilder(url).header("User-Agent", UA_PCS)

    /** 带 query + form body 的 POST（precreate/create 用，参数布局与抓包一致） */
    private suspend fun postQ(
        path: String,
        query: Map<String, String>,
        body: Map<String, String>,
        ua: String = UA_PCS,
    ): String = withContext(Dispatchers.IO) {
        val u = "$BASE$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()
        val form = FormBody.Builder().apply { body.forEach { (k, v) -> add(k, v) } }.build()
        runCatching {
            client.newCall(reqBuilder(u).header("User-Agent", ua).post(form).build())
                .execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
    }

    /** bdstoken（/api/create 等写接口的签名参数；实测浏览器 UA 返回） */
    suspend fun bdstoken(): String {
        val text = runCatching {
            val u = "$BASE/api/gettemplatevariable".toHttpUrl().newBuilder().apply {
                addQueryParameter("fields", "[\"bdstoken\"]")
                addQueryParameter("clienttype", "0")
                addQueryParameter("app_id", APP_ID)
                addQueryParameter("web", "1")
            }.build().toString()
            client.newCall(
                Request.Builder().url(u)
                    .header("User-Agent", UA_WEB)
                    .header("Cookie", auth())
                    .header("Referer", "$BASE/disk/home")
                    .get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        return Regex("\"bdstoken\":\"([^\"]+)\"").find(text)?.groupValues?.get(1).orEmpty()
    }

    /**
     * PCS locatedownload 取下载直链（官方客户端同款参数）。
     * POST body 必须是一个空格；UA 用 netdisk 客户端。
     * 返回 urls[0].url（带签名 CDN 直链，有时效），失败返回空串。
     */
    suspend fun locatedownload(path: String): String = withContext(Dispatchers.IO) {
        val rand = (1..40).map { "0123456789abcdef".random() }.joinToString("")
        val url = PCS_FILE.toHttpUrl().newBuilder().apply {
            addQueryParameter("app_id", APP_ID)
            addQueryParameter("method", "locatedownload")
            addQueryParameter("check_blue", "1")
            addQueryParameter("es", "1")
            addQueryParameter("esl", "1")
            addQueryParameter("path", path)
            addQueryParameter("ver", "4.0")
            addQueryParameter("dtype", "1")
            addQueryParameter("err_ver", "1.0")
            addQueryParameter("ehps", "1")
            addQueryParameter("eck", "1")
            addQueryParameter("vip", "0")
            addQueryParameter("clienttype", "17")
            addQueryParameter("version", "2.2.91.74")
            addQueryParameter("time", (System.currentTimeMillis() / 1000).toString())
            addQueryParameter("rand", rand)
            addQueryParameter("devuid", BaiduPan.deviceId)
            addQueryParameter("channel", "0")
            addQueryParameter("version_app", "12.1.3")
            addQueryParameter("apn_id", "0_-1")
            addQueryParameter("freeisp", "0")
            addQueryParameter("queryfree", "0")
            addQueryParameter("cuid", BaiduPan.deviceId)
            addQueryParameter("use", "1")
        }.build().toString()
        val body = " ".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val text = runCatching {
            client.newCall(reqBuilderPcs(url).post(body).build())
                .execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        val link = runCatching {
            json.parseToJsonElement(text).jsonObject["urls"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                .orEmpty()
        }.getOrDefault("")
        if (link.isBlank()) android.util.Log.w("PanApi", "locatedownload fail: " + text.take(300))
        link
    }

    // ---------------- 网页版视频流（/api/streaming，省流 m3u8） ----------------

    /** 缓存视频页注入的 jsToken（短时有效，进入播放取一次即可）。 */
    private var cachedJsToken: Pair<String, Long>? = null

    /** 请求 /pfile/video HTML，从中提取 window.jsToken。 */
    private suspend fun videoJsToken(path: String): String {
        val cached = cachedJsToken
        if (cached != null && System.currentTimeMillis() - cached.second < 30 * 60_000L && cached.first.isNotBlank()) {
            return cached.first
        }
        val url = "$BASE/pfile/video".toHttpUrl().newBuilder().apply {
            addQueryParameter("path", path)
            addQueryParameter("theme", "light")
            addQueryParameter("view_from", "personal_file")
            addQueryParameter("from", "home")
        }.build().toString()
        val html = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", UA_WEB)
                        .header("Cookie", auth())
                        .get().build()
                ).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
        }
        // HTML 里是 eval(decodeURIComponent('...fn%28%22TOKEN%22%29...'))
        val token = Regex("fn%28%22([0-9A-Fa-f]+)%22%29").find(html)?.groupValues?.get(1)
            ?: Regex("window\\.jsToken\\s*=\\s*\"([0-9A-Fa-f]+)\"").find(html)?.groupValues?.get(1)
            ?: ""
        android.util.Log.i("PanApi", "videoJsToken len=${token.length} htmlLen=${html.length}")
        if (token.isNotBlank()) cachedJsToken = token to System.currentTimeMillis()
        return token
    }

    /**
     * 网页版省流播放：返回 m3u8 文本内容（ts 分片是带签名绝对 URL）。
     * 必须由本方法带 cookie 请求，不能把 /api/streaming URL 直接给播放器
     * （播放器不带 BDUSS cookie 且 adToken 几秒过期，会失败）。
     */
    suspend fun videoM3u8(path: String, quality: Int): String = withContext(Dispatchers.IO) {
        val jsToken = videoJsToken(path)
        val bdstoken = bdstoken()
        val type = when (quality) {
            2160 -> "M3U8_AUTO_2K"
            720 -> "M3U8_AUTO_720"
            360 -> "M3U8_AUTO_360"
            else -> "M3U8_AUTO_480"
        }
        fun build(adToken: String): String = "$BASE/api/streaming".toHttpUrl().newBuilder().apply {
            addQueryParameter("app_id", APP_ID)
            addQueryParameter("clienttype", "0")
            addQueryParameter("channel", "chunlei")
            addQueryParameter("web", "1")
            addQueryParameter("isplayer", "1")
            addQueryParameter("check_blue", "1")
            addQueryParameter("bdstoken", bdstoken)
            addQueryParameter("path", path)
            addQueryParameter("vip", "0")
            addQueryParameter("jsToken", jsToken)
            addQueryParameter("type", type)
            addQueryParameter("trans", "")
            if (adToken.isNotBlank()) addQueryParameter("adToken", adToken)
        }.build().toString()

        fun fetch(url: String): String = runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", UA_WEB)
                    .header("Cookie", auth())
                    .header("Referer", "$BASE/pfile/video")
                    .get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")

        val first = fetch(build(""))
        val adToken = runCatching { json.parseToJsonElement(first).jsonObject["adToken"]?.jsonPrimitive?.content.orEmpty() }
            .getOrDefault("")
        val second = fetch(build(adToken))
        android.util.Log.i("PanApi", "videoStreamUrl type=$type jsToken=${jsToken.length} adToken=${adToken.length} second=${second.take(1)}")
        // 成功时第二步返回 m3u8 文本；失败返回 JSON（errno=31341 权限不足等），不能当 URL 用。
        if (second.trimStart().startsWith("#EXTM3U")) {
            second
        } else {
            val errno = runCatching { json.parseToJsonElement(second).jsonObject["errno"]?.jsonPrimitive?.content.orEmpty() }
                .getOrDefault("")
            throw IllegalStateException(if (errno == "31341") "该画质需要会员" else "获取视频流失败（$errno）")
        }
    }

    // ---------------- 上传（web 三步链：precreate → superfile2 → create，实测通过） ----------------

    /**
     * 上传本地文件到 targetPath（远端完整路径）。
     * 每片 4MB 计算真实 md5；秒传命中（return_type=2）直接完成。
     * onProgress 回调已上传字节数。
     */
    /** 原位上传：直接读本地文件。 */
    suspend fun uploadFile(targetPath: String, file: java.io.File, onProgress: (Long) -> Unit) =
        uploadStream(targetPath, file.length(), { file.inputStream() }, onProgress)

    /**
     * 原位上传（不落缓存）：上传源由 openStream 提供——
     * 本地路径给 FileInputStream，第三方分享给 contentResolver.openInputStream(uri)，
     * 与 PanNative 的做法一致：不先复制一份到缓存。
     */
    suspend fun uploadStream(
        targetPath: String,
        size: Long,
        openStream: () -> java.io.InputStream,
        onProgress: (Long) -> Unit,
    ) {
        if (size <= 0) throw IllegalStateException("空文件无法上传")

        val sliceCount = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt().coerceAtLeast(1)
        val md5s = ArrayList<String>(sliceCount)
        openStream().use { input ->
            val buf = ByteArray(BLOCK_SIZE.toInt())
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md5s.add(md5hex(buf, n))
            }
        }
        val blockList = md5s.joinToString(",", "[", "]") { "\"$it\"" }

        // 1) 预创建（rtype=2：同名自动重命名）
        val pre = postQ(
            "/api/precreate",
            mapOf("app_id" to APP_ID, "channel" to "chunlei", "clienttype" to "0", "web" to "1"),
            mapOf(
                "path" to targetPath,
                "size" to size.toString(),
                "autoinit" to "1",
                "rtype" to "2",
                "block_list" to blockList,
            ),
        )
        val preResp = parse<PrecreateResponse>(pre) ?: throw IllegalStateException("预创建响应解析失败")
        if (preResp.errno != 0) {
            throw IllegalStateException("创建上传任务失败: ${errnoMessage(preResp.errno, preResp.showMsg)}")
        }
        if (preResp.returnType == 2) return // 秒传命中

        // 2) 分片上传（multipart，field 为 file，partseq 从 0 开始）
        var uploaded = 0L
        openStream().use { input ->
            val buf = ByteArray(BLOCK_SIZE.toInt())
            var seq = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (!uploadSlice(preResp.uploadId, targetPath, seq, buf.copyOf(n))) {
                    throw IllegalStateException("分片上传失败（第 ${seq + 1}/$sliceCount 片）")
                }
                seq++
                uploaded += n
                onProgress(uploaded)
            }
        }

        // 3) 落盘（/api/create + bdstoken）
        val cr = postQ(
            "/api/create",
            mapOf(
                "app_id" to APP_ID,
                "channel" to "chunlei",
                "clienttype" to "0",
                "web" to "1",
                "bdstoken" to bdstoken(),
            ),
            mapOf(
                "path" to targetPath,
                "size" to size.toString(),
                "uploadid" to preResp.uploadId,
                "block_list" to blockList,
                "rtype" to "2",
            ),
        )
        val crResp = parse<SimpleResponse>(cr) ?: SimpleResponse(errno = -1)
        if (crResp.errno != 0) {
            throw IllegalStateException("上传落盘失败: ${errnoMessage(crResp.errno, crResp.showMsg)}")
        }
    }

    /** superfile2 单片上传；成功判定：errno=0 或返回 md5 */
    private suspend fun uploadSlice(uploadId: String, path: String, seq: Int, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "blob",
                    data.toRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
            val url = PCS_UPLOAD.toHttpUrl().newBuilder().apply {
                addQueryParameter("method", "upload")
                addQueryParameter("app_id", APP_ID)
                addQueryParameter("type", "tmpfile")
                addQueryParameter("partseq", seq.toString())
                addQueryParameter("uploadid", uploadId)
                addQueryParameter("path", path)
            }.build().toString()
            runCatching {
                client.newCall(reqBuilderPcs(url).post(body).build()).execute().use { resp ->
                    val t = resp.body?.string().orEmpty()
                    val r = parse<UploadSliceResponse>(t)
                    r != null && (r.errno == 0 || r.md5.isNotBlank())
                }
            }.getOrDefault(false)
        }

    private fun md5hex(data: ByteArray, len: Int): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(data.copyOf(len))
            .joinToString("") { String.format("%02x", it) }

}
