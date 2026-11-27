package com.panclone.app.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.panclone.app.data.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录态与设置存储。BDUSS/STOKEN 等于账号完全控制权，必须加密存储。
 */
class SessionStore(context: Context) {

    private val appCtx = context.applicationContext

    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appCtx,
            "secure_session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val prefs: SharedPreferences by lazy {
        appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    /** 小飞机登录态：网页版登录后有 token，即视为已登录 */
    fun refreshLoggedIn() {
        _loggedIn.value = feiToken.isNotBlank()
    }

    private val _avatarUrlFlow = MutableStateFlow("")
    /** 头像 URL（响应式，供底栏「我的」显示真实头像） */
    val avatarUrlFlow: StateFlow<String> = _avatarUrlFlow.asStateFlow()

    var cookie: String
        get() = secure.getString(KEY_COOKIE, "").orEmpty()
        private set(v) {
            secure.edit().putString(KEY_COOKIE, v).apply()
            _loggedIn.value = v.isNotBlank()
        }

    // ==================== 小飞机网盘（feijipan.com）登录态与分享上下文 ====================

    /** 登录模式：分享解析（匿名可）或个人网盘（需 token） */
    var feiMode: String
        get() = prefs.getString("fei_mode", "SHARE").orEmpty()
        set(v) = prefs.edit().putString("fei_mode", v).apply()

    /** 当前分享 id（分享模式导航/取链必需） */
    var feiShareId: String
        get() = prefs.getString("fei_share_id", "").orEmpty()
        set(v) = prefs.edit().putString("fei_share_id", v).apply()

    /** 当前分享提取码 */
    var feiCode: String
        get() = prefs.getString("fei_code", "").orEmpty()
        set(v) = prefs.edit().putString("fei_code", v).apply()

    /** 分享标题（用于界面展示当前在浏览哪个分享） */
    var feiShareName: String
        get() = prefs.getString("fei_share_name", "").orEmpty()
        set(v) = prefs.edit().putString("fei_share_name", v).apply()

    /** 个人网盘登录 token（加密存，等于账号控制权） */
    var feiToken: String
        get() = secure.getString(KEY_FEI_TOKEN, "").orEmpty()
        set(v) {
            secure.edit().putString(KEY_FEI_TOKEN, v).apply()
            refreshLoggedIn()
        }

    /** 个人网盘设备标识 uuid（网页登录从 vuex 取，取链时与服务端保持一致） */
    var feiUuid: String
        get() = prefs.getString("fei_uuid", "").orEmpty()
        set(v) = prefs.edit().putString("fei_uuid", v).apply()

    /** 个人网盘用户 id（取链拼 downloadId 用） */
    var feiUserId: String
        get() = secure.getString(KEY_FEI_USER_ID, "").orEmpty()
        set(v) = secure.edit().putString(KEY_FEI_USER_ID, v).apply()

    /** 保存网页版 WebView 登录态：token 加密存，uuid/userId 存 prefs */
    fun saveWebLogin(token: String, uuid: String, userId: String) {
        feiMode = "PERSONAL"
        feiUuid = uuid
        feiUserId = userId
        feiToken = token
        refreshLoggedIn()
    }

    /** 保存当前分享上下文并切换到分享模式 */
    fun saveShareContext(shareId: String, code: String, name: String) {
        feiMode = "SHARE"
        feiShareId = shareId
        feiCode = code
        feiShareName = name
        refreshLoggedIn()
    }

    /** 保存个人网盘登录态并切换到个人模式 */
    fun savePersonalLogin(token: String, userId: String) {
        feiMode = "PERSONAL"
        feiToken = token
        feiUserId = userId
        refreshLoggedIn()
    }

    var userName: String
        get() = prefs.getString("user_name", "").orEmpty()
        set(v) = prefs.edit().putString("user_name", v).apply()

    var avatarUrl: String
        get() = prefs.getString("avatar_url", "").orEmpty()
        set(v) {
            prefs.edit().putString("avatar_url", v).apply()
            _avatarUrlFlow.value = v
        }

    init {
        _avatarUrlFlow.value = prefs.getString("avatar_url", "").orEmpty()
        refreshLoggedIn()
    }

    /** uk 用户 id，分享、上传等接口需要 */
    var uk: Long
        get() = prefs.getLong("uk", 0L)
        set(v) = prefs.edit().putLong("uk", v).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM")!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString("theme_mode", v.name).apply()

    var downloadDir: String
        get() = prefs.getString("download_dir", "/storage/emulated/0/BaiduNetdisk").orEmpty()
        set(v) = prefs.edit().putString("download_dir", v).apply()

    var downloadThreads: Int
        get() = prefs.getInt("threads_v2", 1280)
        set(v) = prefs.edit().putInt("threads_v2", v.coerceIn(1, 1280)).apply()


    /** 文件页排序偏好 */
    var sortPref: String
        get() = prefs.getString("sort_pref", "NAME").orEmpty()
        set(v) = prefs.edit().putString("sort_pref", v).apply()

    var sortAsc: Boolean
        get() = prefs.getBoolean("sort_asc", true)
        set(v) = prefs.edit().putBoolean("sort_asc", v).apply()

    /** 文件页展示方式：默认平铺，可在排序面板切换列表。 */
    var viewMode: String
        get() = prefs.getString("view_mode", "GRID").orEmpty()
        set(v) = prefs.edit().putString("view_mode", v).apply()

    fun saveCookie(rawCookie: String) {
        cookie = rawCookie.trim()
    }

    fun logout() {
        cookie = ""
        userName = ""
        avatarUrl = ""
        uk = 0
        feiShareId = ""
        feiCode = ""
        feiShareName = ""
        feiToken = ""
        feiUuid = ""
        feiUserId = ""
        feiMode = "SHARE"
        refreshLoggedIn()
    }

    companion object {
        private const val KEY_COOKIE = "bduss_cookie"
        private const val KEY_FEI_TOKEN = "fei_token"
        private const val KEY_FEI_USER_ID = "fei_user_id"

        /** 从完整 cookie 串里提取 BDUSS/STOKEN，供请求头使用 */
        fun extractAuth(cookie: String): String {
            val keep = listOf("BDUSS", "BDUSS_BFESS", "STOKEN", "PANWEB")
            return cookie.split(";")
                .map { it.trim() }
                .filter { kv -> keep.any { kv.startsWith("$it=") } }
                .joinToString("; ")
        }
    }
}
