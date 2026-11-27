package com.panclone.app.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.store.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 网页版登录检测状态 */
sealed interface LoginState {
    data object Idle : LoginState
    data object Detecting : LoginState
    data object Success : LoginState
    data class Error(val message: String) : LoginState
}

/**
 * 小飞机网盘网页版登录：
 * WebView 打开网页版控制台，从网页 localStorage 的 vuex 里抓 appToken/uuid，
 * 拿到后查 userId 并保存登录态，全程用户只需在网页里正常登录一次。
 */
class LoginViewModel : ViewModel() {
    private val session: SessionStore = App.instance.session
    private val feiji = App.instance.feiji

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 是否有一次登录校验在飞行中，避免自动轮询与手动「保存」重复触发 */
    private var checking = false

    /** WebView 抓到 vuex 原始 JSON 字符串：提取 token/uuid，校验通过则完成登录 */
    fun onVuexRaw(raw: String) {
        val extracted = extractCredentials(raw)
        if (extracted == null) {
            _state.value = LoginState.Error("未在网页里找到登录信息，请先登录网页版")
            return
        }
        checking = true
        viewModelScope.launch {
            try {
                completeLogin(extracted.first, extracted.second)
            } finally {
                checking = false
            }
        }
    }

    /** 校验登录态并落地：查 userId → 保存网页登录 → success */
    suspend fun completeLogin(token: String, uuid: String) {
        _state.value = LoginState.Detecting
        try {
            val userId = feiji.getUserId(token)
            session.saveWebLogin(token, uuid, userId)
            _state.value = LoginState.Success
        } catch (e: Exception) {
            _state.value = LoginState.Error(e.message ?: "登录校验失败，请稍后重试")
        }
    }

    /** 清除一次性 toast */
    fun consumeToast() {
        _toast.value = null
    }

    fun reset() {
        _state.value = LoginState.Idle
        _toast.value = null
        checking = false
    }

    companion object {
        /** token：长度 16-16384，只允许 token 安全字符 */
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9._~+/=-]+$")
        private const val MAX_TOKEN_LEN = 16384
        private const val MIN_TOKEN_LEN = 16

        /**
         * 从 vuex 原始值里提取 token 与 uuid。
         * vuex 用 common 字段管理鉴权：优先 common.appToken，退回 common.accessToken；
         * common 为空时退回直接解析顶层。返回 null 表示没抓到有效登录信息。
         */
        fun extractCredentials(raw: String?): Pair<String, String>? {
            if (raw.isNullOrBlank() || raw == "null") return null
            val decoded = decodeJsString(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
            val source = root.optJSONObject("common") ?: root
            val token = sanitizeToken(
                source.optString("appToken", "").ifBlank { source.optString("accessToken", "") },
            ) ?: return null
            val uuid = source.optString("uuid", "").trim()
            return token to uuid
        }

        /**
         * evaluateJavascript 回传的字符串是被 JSON 编码过的一层（带首尾引号、内部引号被转义），
         * 先用 JSONObject 还原成真值；不是引号包裹就当原始串用。
         */
        private fun decodeJsString(raw: String): String {
            if (!raw.startsWith("\"")) return raw
            return runCatching { JSONObject(raw).toString() }.getOrElse { raw }
        }

        /** 校验并清洗 token：去引号与首尾空白，长度/字符集/占位值都过关才返回 */
        private fun sanitizeToken(value: String): String? {
            val token = value.trim().trim('"')
            if (token.length !in MIN_TOKEN_LEN..MAX_TOKEN_LEN) return null
            if (!TOKEN_PATTERN.matches(token)) return null
            if (token == "null" || token == "undefined") return null
            return token
        }
    }
}
