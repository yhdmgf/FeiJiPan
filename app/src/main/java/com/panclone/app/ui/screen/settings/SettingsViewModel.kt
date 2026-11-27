package com.panclone.app.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.model.QuotaResponse
import com.panclone.app.data.model.UserInfoResponse
import com.panclone.app.data.model.VipQueryResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val api = App.instance.api

    private val _quota = MutableStateFlow(QuotaResponse())
    val quota: StateFlow<QuotaResponse> = _quota.asStateFlow()

    private val _user = MutableStateFlow(UserInfoResponse())
    val user: StateFlow<UserInfoResponse> = _user.asStateFlow()

    private val _vip = MutableStateFlow<VipQueryResponse?>(null)
    val vip: StateFlow<VipQueryResponse?> = _vip.asStateFlow()

    /** 主题切换后需要一个状态变化让根 Composable 重组 */
    private val _themeTick = MutableStateFlow(0)
    val themeTick: StateFlow<Int> = _themeTick.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _quota.value = api.quota()
            val u = api.userInfo()
            _user.value = u
            if (u.errno == 0) {
                App.instance.session.userName = u.baiduName.ifBlank { u.netdiskName }
                App.instance.session.avatarUrl = u.avatarUrl
            }
            _vip.value = api.vipQuery()
        }
    }

    fun requestRecompose() { _themeTick.value = _themeTick.value + 1 }

    /** 下载链路自检：取根目录第一个文件试下载首段 */
    fun probeDownload(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val list = api.list("/", num = 50)
            val file = list.list.firstOrNull { it.isDir == 0 }
            if (file == null) {
                onResult("根目录没有可测的文件")
                return@launch
            }
            val r = com.panclone.app.data.net.DownloadProbe.probe(file.fsId, file.path)
            onResult(
                if (r.ok) "✅ ${file.name}\nvia=${r.via} HTTP=${r.code} 收到${r.bytes}字节"
                else "❌ ${file.name}\n${r.detail}\nnative=${com.panclone.app.data.net.BaiduPan.isReady}"
            )
        }
    }
}
