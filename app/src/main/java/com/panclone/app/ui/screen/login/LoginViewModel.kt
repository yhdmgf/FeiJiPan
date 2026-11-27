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

class LoginViewModel : ViewModel() {
    private val session: SessionStore = App.instance.session
    private val api = App.instance.api

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /** 用 cookie 完成登录并校验 */
    fun loginWithCookie(rawCookie: String) {
        // 保存完整 cookie：gettemplatevariable 取 token 依赖 PANPSC 等 web 会话字段，
        // 过滤成只剩 BDUSS/STOKEN 会导致验证码取不到 token。
        val effective = rawCookie.trim()
        if (!effective.contains("BDUSS=")) {
            _state.value = LoginState.Error("Cookie 里没有 BDUSS，请重新复制完整 Cookie")
            return
        }
        _state.value = LoginState.Loading
        viewModelScope.launch {
            session.saveCookie(effective)
            val info = api.userInfo()
            if (api.isAuthError(info.errno)) {
                session.logout()
                _state.value = LoginState.Error("登录态无效或已过期，请重新登录")
                return@launch
            }
            // getinfo 偶尔返回空名字，此时保留已有值，不覆盖成空
            val name = info.netdiskName.ifBlank { info.baiduName }
            if (name.isNotBlank()) session.userName = name
            if (info.avatarUrl.isNotBlank()) {
                session.avatarUrl =
                    if (info.avatarUrl.startsWith("http")) info.avatarUrl else "https:${info.avatarUrl}"
            }
            if (info.uk > 0) session.uk = info.uk
            _state.value = LoginState.Success
        }
    }

    fun reset() { _state.value = LoginState.Idle }
}
