package com.panclone.app.ui.screen.parse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.net.FeiJiPanFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 解析页状态。
 *
 * 解析 = 输入小飞机分享链接 + 提取码 → 拉取分享文件列表（文件夹/文件）
 * → 点文件取带签名下载直链 → 二次点「下载」加入下载任务（照 wenxi 交互）。
 */
data class ParseUiState(
    val url: String = "",
    val code: String = "",
    val shareId: String = "",
    /** 当前目录文件列表（文件夹在前、文件在后） */
    val files: List<FeiJiPanFile> = emptyList(),
    /** 当前所在目录 id，空串表示分享根目录 */
    val currentFolderId: String = "",
    /** 目录栈：进入子目录时压栈，返回上级时弹栈 */
    val folderStack: List<FeiJiPanFile> = emptyList(),
    val loading: Boolean = false,
    /** 一次性提示（Toast），消费后置空 */
    val message: String = "",
    /** 选中待下载的文件 */
    val selectedFile: FeiJiPanFile? = null,
    /** 已解析出的带签名下载直链 */
    val downloadUrl: String = "",
    val showDownloadSheet: Boolean = false,
)

/**
 * 从分享链接提取 shareId 与提取码（照 wenxi domain/links.dart 规则）。
 *
 * 支持：
 * - 明文路径 `https://www.feijipan.com/s/<shareId>`
 * - hash 路由 `https://www.feijipan.com/#/s/<shareId>`
 * - shareId：4-64 位字母数字
 * - 提取码：URL `?code=` 的 4 位字母数字，或用户在提取码框手动输入
 *
 * @return shareId 到 Pair 的第一个，提取码到第二个（URL 无 code 时为空串）；无法识别返回 null
 */
internal fun parseShareLink(url: String): Pair<String, String>? {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return null
    // 去掉 hash 前的 `#`，把 `#/s/xxx` 统一成 `/s/xxx` 便于匹配
    val normalized = trimmed.replace("#", "")
    val shareRegex = Regex("""/s/([A-Za-z0-9]{4,64})""")
    val shareId = shareRegex.find(normalized)?.groupValues?.get(1) ?: return null
    val codeRegex = Regex("""[?&]code=([A-Za-z0-9]{4})""")
    val code = codeRegex.find(normalized)?.groupValues?.get(1) ?: ""
    return shareId to code
}

/** 排序：文件夹在前，同类型按名称；照 wenxi 分享列表展示顺序。 */
private fun sortEntries(entries: List<FeiJiPanFile>): List<FeiJiPanFile> =
    entries.sortedWith(
        compareByDescending<FeiJiPanFile> { it.isDirectory }
            .thenBy { it.name.lowercase() },
    )

class ParseViewModel : ViewModel() {

    private val _state = MutableStateFlow(ParseUiState())
    val state: StateFlow<ParseUiState> = _state.asStateFlow()

    private val feiji get() = App.instance.feiji

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    fun onCodeChange(value: String) = _state.update {
        // 提取码固定 4 位字母数字，超出截断
        it.copy(code = value.take(4).filter { c -> c.isLetterOrDigit() })
    }

    fun consumeMessage() = _state.update { it.copy(message = "") }

    /**
     * 解析分享链接：提取 shareId + code → 从根目录翻页拉全 → files。
     * code 优先用用户输入，其次用 URL `?code=` 自带的。
     */
    fun parse() {
        val current = _state.value
        val extracted = parseShareLink(current.url)
        if (extracted == null) {
            _state.update { it.copy(message = "无法识别小飞机分享链接") }
            return
        }
        val (shareId, urlCode) = extracted
        val code = current.code.ifBlank { urlCode }
        if (code.isBlank()) {
            _state.update { it.copy(message = "请输入提取码") }
            return
        }
        _state.update {
            it.copy(
                shareId = shareId,
                code = code,
                loading = true,
                currentFolderId = "",
                folderStack = emptyList(),
                files = emptyList(),
                selectedFile = null,
                downloadUrl = "",
                showDownloadSheet = false,
            )
        }
        viewModelScope.launch {
            try {
                val entries = loadAll(folderId = null, shareId = shareId, code = code)
                _state.update { it.copy(files = sortEntries(entries), loading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        message = e.message?.takeIf { m -> m.isNotBlank() } ?: "解析失败，请稍后再试",
                    )
                }
            }
        }
    }

    /** 进入子目录：翻页拉全该目录 → files，并压栈记录上级。 */
    fun enterFolder(folder: FeiJiPanFile) {
        if (!folder.isDirectory) return
        val current = _state.value
        if (current.loading) return
        _state.update { it.copy(loading = true, currentFolderId = folder.id) }
        viewModelScope.launch {
            try {
                val entries = loadAll(folderId = folder.id, shareId = current.shareId, code = current.code)
                _state.update {
                    it.copy(
                        files = sortEntries(entries),
                        folderStack = it.folderStack + folder,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        currentFolderId = it.folderStack.lastOrNull()?.id ?: "",
                        message = e.message?.takeIf { m -> m.isNotBlank() } ?: "加载失败，请稍后再试",
                    )
                }
            }
        }
    }

    /** 返回上级目录：弹栈；已在根目录则清空回根列表。 */
    fun goUp() {
        val current = _state.value
        if (current.loading) return
        if (current.folderStack.isEmpty()) return
        val newStack = current.folderStack.dropLast(1)
        val parentId = newStack.lastOrNull()?.id ?: ""
        _state.update { it.copy(loading = true, folderStack = newStack, currentFolderId = parentId) }
        viewModelScope.launch {
            try {
                val entries = if (parentId.isBlank()) {
                    loadAll(folderId = null, shareId = current.shareId, code = current.code)
                } else {
                    loadAll(folderId = parentId, shareId = current.shareId, code = current.code)
                }
                _state.update { it.copy(files = sortEntries(entries), loading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        message = e.message?.takeIf { m -> m.isNotBlank() } ?: "加载失败，请稍后再试",
                    )
                }
            }
        }
    }

    /**
     * 点击文件：取带签名下载直链（分享解析不登录，userId/token 传空串）。
     * 成功后弹出下载卡片。
     */
    fun selectFile(file: FeiJiPanFile) {
        if (file.isDirectory) return
        val current = _state.value
        if (current.loading) return
        _state.update { it.copy(loading = true, selectedFile = file) }
        viewModelScope.launch {
            try {
                val url = feiji.resolveShareFileUrl(
                    shareId = current.shareId,
                    code = current.code,
                    fileId = file.id,
                    userId = "",
                    token = "",
                )
                _state.update {
                    it.copy(
                        loading = false,
                        downloadUrl = url,
                        showDownloadSheet = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        selectedFile = null,
                        message = e.message?.takeIf { m -> m.isNotBlank() } ?: "获取下载链接失败",
                    )
                }
            }
        }
    }

    fun dismissDownloadSheet() = _state.update {
        it.copy(showDownloadSheet = false, selectedFile = null, downloadUrl = "")
    }

    /**
     * 二次点击「下载」：加入下载任务（下载目录用共享 session.downloadDir）。
     * remotePath 存小飞机 fileId 字符串，fsId 用 fileId 的数值形式（不可解析则 0）。
     */
    fun confirmDownload() {
        val current = _state.value
        val file = current.selectedFile ?: return
        val app = App.instance
        val dirPath = app.session.downloadDir.ifBlank {
            File(app.getExternalFilesDir(null), "downloads").absolutePath
        }
        // 分享解析下载：直接用已解析的签名直链，不走 resolveDownloadUrl（那里用的是登录态 session 的 shareId，会拿错链接）
        val url = current.downloadUrl
        if (url.isBlank()) return
        app.transfers.addDirectDownload(
            name = file.name,
            url = url,
            size = file.sizeBytes,
            localDir = File(dirPath),
        )
        _state.update {
            it.copy(
                showDownloadSheet = false,
                selectedFile = null,
                downloadUrl = "",
                message = "已加入下载任务",
            )
        }
    }

    /**
     * 翻页拉全某个分享目录：从第 1 页循环到 totalPage（照 wenxi 循环翻页）。
     * @param folderId null 表示分享根目录，否则为子目录
     */
    private suspend fun loadAll(folderId: String?, shareId: String, code: String): List<FeiJiPanFile> {
        val entries = mutableListOf<FeiJiPanFile>()
        var page = 1
        while (true) {
            val data = if (folderId == null) {
                feiji.listShareRoot(shareId, code, page)
            } else {
                feiji.listShareFolder(shareId, code, folderId, page)
            }
            entries.addAll(data.files)
            val totalPage = data.totalPage
            if (totalPage <= page || data.files.isEmpty()) break
            page++
            // 防御性上限，避免服务端 totalPage 异常导致死循环
            if (page > 500) break
        }
        return entries
    }
}
