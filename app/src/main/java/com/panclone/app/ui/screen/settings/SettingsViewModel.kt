package com.panclone.app.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：小飞机网盘版。
 * 百度专属的配额/VIP/用户信息/下载探测已移除；仅保留主题重组 tick 与下载自检。
 */
class SettingsViewModel : ViewModel() {

    /** 主题切换后需要一个状态变化让根 Composable 重组 */
    private val _themeTick = MutableStateFlow(0)
    val themeTick: StateFlow<Int> = _themeTick.asStateFlow()

    /** 当前登录身份展示名（分享模式=分享标题；个人模式=用户 id） */
    private val _accountLabel = MutableStateFlow("")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val s = App.instance.session
        _accountLabel.value = if (s.feiMode == "PERSONAL") {
            s.feiUserId.ifBlank { "个人网盘" }
        } else {
            s.feiShareName.ifBlank { "分享解析" }
        }
    }

    fun requestRecompose() {
        _themeTick.value = _themeTick.value + 1
    }

    /** 下载链路自检：解析当前分享/个人网盘根目录，取第一个文件试取直链首段。 */
    fun probeDownload(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val repo = App.instance.repo
            val list = when (val r = repo.list("", page = 1)) {
                is com.panclone.app.data.repo.FileRepository.Result.Ok -> r.data
                is com.panclone.app.data.repo.FileRepository.Result.Err -> {
                    onResult("❌ 列目录失败：${r.message}")
                    return@launch
                }
            }
            val file = list.firstOrNull { !it.isDirectory }
            if (file == null) {
                onResult("根目录没有可测的文件")
                return@launch
            }
            when (val d = repo.dlink(file.path)) {
                is com.panclone.app.data.repo.FileRepository.Result.Ok -> {
                    val url = d.data
                    onResult("✅ ${file.name}\n直链已获取：${url.take(60)}…")
                }
                is com.panclone.app.data.repo.FileRepository.Result.Err ->
                    onResult("❌ ${file.name}\n${d.message}")
            }
        }
    }
}
