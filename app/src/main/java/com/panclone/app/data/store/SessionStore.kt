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

    private val _loggedIn = MutableStateFlow(cookie.isNotBlank())
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _avatarUrlFlow = MutableStateFlow("")
    /** 头像 URL（响应式，供底栏「我的」显示真实头像） */
    val avatarUrlFlow: StateFlow<String> = _avatarUrlFlow.asStateFlow()

    var cookie: String
        get() = secure.getString(KEY_COOKIE, "").orEmpty()
        private set(v) {
            secure.edit().putString(KEY_COOKIE, v).apply()
            _loggedIn.value = v.isNotBlank()
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
    }

    companion object {
        private const val KEY_COOKIE = "bduss_cookie"

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
