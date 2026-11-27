package com.panclone.app.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.store.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data object Success : LoginState
    data class Error(val message: String) : LoginState
}

/**
 * 小飞机网盘登录：
 *  - 分享解析：粘贴分享链接 + 提取码，匿名即可浏览下载（主入口）。
 *  - 个人网盘：粘贴 access token + 用户 id（从网页登录获取），浏览个人文件。
 */
class LoginViewModel : ViewModel() {
    private val session: SessionStore = App.instance.session
    private val feiji = App.instance.feiji

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /** 解析分享：从链接提取 shareId/提取码，验证通过后保存分享上下文。 */
    fun parseShare(rawUrl: String, rawCode: String) {
        val url = rawUrl.trim()
        val shareId = extractShareId(url)
        if (shareId == null) {
            _state.value = LoginState.Error("无法识别小飞机分享链接，请粘贴 feijipan.com/s/… 完整链接")
            return
        }
        // 提取码：优先用户输入，其次从链接 ?code= 提取
        val code = rawCode.trim().ifEmpty { extractCode(url) }
        _state.value = LoginState.Loading
        viewModelScope.launch {
            try {
                val data = feiji.listShareRoot(shareId, code, 1)
                val name = data.files.firstOrNull()?.name ?: "小飞机分享"
                session.saveShareContext(shareId, code, name)
                _state.value = LoginState.Success
            } catch (e: Exception) {
                _state.value = LoginState.Error(e.message ?: "解析失败，请检查链接与提取码")
            }
        }
    }

    /** 个人网盘登录：校验 token 后保存登录态。 */
    fun loginPersonal(token: String, userId: String) {
        val t = token.trim()
        val u = userId.trim()
        if (t.isBlank() || u.isBlank()) {
            _state.value = LoginState.Error("请填写 access token 与用户 id")
            return
        }
        _state.value = LoginState.Loading
        viewModelScope.launch {
            try {
                feiji.listPersonal("", 1, t)
                session.savePersonalLogin(t, u)
                _state.value = LoginState.Success
            } catch (e: Exception) {
                _state.value = LoginState.Error(e.message ?: "登录失败，请检查 token 与用户 id")
            }
        }
    }

    fun reset() {
        _state.value = LoginState.Idle
    }

    companion object {
        /** 小飞机分享链接：/s/<shareId>（4-64位）或 hash 路由 /#/s/<shareId> */
        private val SHARE_ROUTE = Regex("""/s/([A-Za-z0-9]{4,64})""")
        private val CODE_PARAM = Regex("""[?&]code=([A-Za-z0-9]{4})""")

        /** 从分享链接提取 shareId（支持明文路径与 hash 路由） */
        fun extractShareId(url: String): String? {
            val frag = url.substringAfter('#', "")
            val target = if (frag.startsWith("/")) frag else url
            return SHARE_ROUTE.find(target)?.groupValues?.get(1)
        }

        /** 从分享链接 ?code= 提取提取码 */
        fun extractCode(url: String): String =
            CODE_PARAM.find(url)?.groupValues?.get(1).orEmpty()
    }
}
