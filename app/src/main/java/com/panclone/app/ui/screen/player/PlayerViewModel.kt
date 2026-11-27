package com.panclone.app.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.repo.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: String = "",
    val preparing: Boolean = true,
    val error: String = "",
    /** 播放地址；放进 state，加载完成后触发重组 */
    val url: String = "",
    /** 切画质后要恢复的进度 */
    val resumePositionMs: Long = 0L,
    /** 实际生效的画质（成功档位），用于 UI 真实高亮，不写偏好 */
    val quality: Int = 480,
    /** 网页支持档位（实测）：0=原画，2160=2K，720/480/360 */
    val qualityOptions: List<Int> = listOf(0, 2160, 720, 480, 360),
    /** 画质回落等非阻塞提示 */
    val notice: String = "",
)

/**
 * 播放页 ViewModel。
 * 每次打开都从 480P 起播；取链失败自动依次回落（含原画兜底），
 * 不把上次档位写入偏好，避免影响别的视频。
 */
class PlayerViewModel : ViewModel() {
    private val app = App.instance
    private val repo = FileRepository(app.api)

    private var currentFile: PanFile? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 默认档位：每次播放都从 480 开始，不记住上次 */
    companion object {
        private const val DEFAULT_QUALITY = 480
    }

    fun load(file: PanFile) {
        currentFile = file
        loadQuality(DEFAULT_QUALITY, keepPosition = false, fromUser = false)
    }

    /** 用户手动切档：保留进度；命中失败自动回落，不写偏好。 */
    fun setQuality(quality: Int, currentPositionMs: Long = 0L) {
        loadQuality(quality, keepPosition = true, position = currentPositionMs, fromUser = true)
    }

    private suspend fun fetch(file: PanFile, quality: Int): FileRepository.Result<String> =
        if (quality == 0) {
            runCatching { repo.dlink(file.path) }.getOrElse { FileRepository.Result.Err(it.message ?: "获取直链失败") }
        } else {
            repo.videoStream(file.path, quality)
        }

    private fun label(q: Int): String = when (q) {
        0 -> "原画"
        2160 -> "2K"
        else -> "${q}P"
    }

    /** 失败回落链：所选档 → 省流档 → 原画兜底。始终包含原画，保证能播。 */
    private fun candidatesFor(quality: Int): List<Int> = buildList {
        add(quality)
        when (quality) {
            DEFAULT_QUALITY -> { add(360); add(0) }
            360 -> { add(DEFAULT_QUALITY); add(0) }
            0 -> { add(DEFAULT_QUALITY); add(360) }
            else -> { add(DEFAULT_QUALITY); add(360); add(0) }
        }
    }.distinct()

    private fun loadQuality(quality: Int, keepPosition: Boolean, position: Long = 0L, fromUser: Boolean) {
        val file = currentFile ?: return
        _state.value = _state.value.copy(
            title = file.name,
            preparing = true,
            error = "",
            url = "",
            notice = "",
            resumePositionMs = if (keepPosition) position else 0L,
        )
        viewModelScope.launch {
            var lastError = ""
            var chosen: Pair<String, Int>? = null
            var notice = ""
            for ((index, q) in candidatesFor(quality).withIndex()) {
                when (val r = fetch(file, q)) {
                    is FileRepository.Result.Ok -> {
                        chosen = r.data to q
                        if (index > 0) notice = "${label(quality)}不可用，已切换到${label(q)}"
                        break
                    }
                    is FileRepository.Result.Err -> lastError = r.message
                }
            }
            if (chosen != null) {
                _state.value = _state.value.copy(
                    preparing = false,
                    url = chosen.first,
                    quality = chosen.second,  // 真实生效档位，UI 据此高亮
                    notice = notice,
                )
            } else {
                // 全失败：保留当前档位标记，仅显示错误；错误界面仍可操作切档重试
                _state.value = _state.value.copy(
                    preparing = false,
                    error = lastError.ifBlank { "无法播放该视频" },
                    notice = if (fromUser) "" else "已尝试全部画质均失败，可点下方画质重试",
                )
            }
        }
    }

    fun clearNotice() {
        if (_state.value.notice.isNotBlank()) _state.value = _state.value.copy(notice = "")
    }

    fun rememberProgress(position: Long, duration: Long) { /* 由 saveProgress 落盘 */ }
    fun saveProgress(position: Long, duration: Long) { /* 视频不再保存进度 */ }
}
