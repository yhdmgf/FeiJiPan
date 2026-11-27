package com.panclone.app.ui.screen.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.repo.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortField(val label: String) {
    NAME("名称"),
    TIME("修改时间"),
    SIZE("大小"),
    TYPE("类型"),
}

enum class ViewMode { LIST, GRID }

/** 目录栈的一层 */
data class Crumb(val path: String, val name: String)

data class ScrollPosition(val index: Int, val offset: Int)

data class FilesUiState(
    val path: String = "/",
    val crumbs: List<Crumb> = listOf(Crumb("/", "全部文件")),
    val items: List<PanFile> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
    val empty: Boolean = false,
    // 排序 / 展示
    val sortBy: SortField = SortField.NAME,
    val sortAsc: Boolean = true,
    val viewMode: ViewMode = ViewMode.GRID,
    // 搜索
    val searching: Boolean = false,
    val keyword: String = "",
    /** 每次搜索自增，用于列表强制从顶部重建 */
    val searchSeq: Int = 0,
    // 多选
    val selectMode: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val authFailed: Boolean = false,
    val showCreate: Boolean = false,
    val message: String = "",
    val highlightedFsId: Long? = null,
) {
    val selectedFiles: List<PanFile> get() = items.filter { it.fsId in selected }
    val allSelected: Boolean get() = items.isNotEmpty() && selected.size == items.size
}

/** 分享弹层状态：files 非空表示弹层打开；done=true 表示已创建/已更新 */
data class ShareUiState(
    val files: List<PanFile> = emptyList(),
    val fsIds: List<Long> = emptyList(),
    val creating: Boolean = false,
    val done: Boolean = false,
    val shareId: Long = 0,
    val link: String = "",
    val pwd: String = "",
    val qrcodeUrl: String = "",
    val error: String = "",
)

class FilesViewModel : ViewModel() {
    private val repo = FileRepository(App.instance.api)
    private val session = App.instance.session

    private val _state = MutableStateFlow(
        FilesUiState(
            sortBy = FileRepository.parseSort(session.sortPref),
            sortAsc = session.sortAsc,
            viewMode = runCatching { ViewMode.valueOf(session.viewMode) }.getOrDefault(ViewMode.GRID),
        )
    )
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private val scrollPositions = mutableMapOf<Pair<String, ViewMode>, ScrollPosition>()
    /** 目录快照：后退时若目录未被修改则直接复用，不再请求。 */
    private val snapshots = mutableMapOf<String, List<PanFile>>()
    /** 本次会话中被修改过的路径（含其父目录），用于判定能否复用快照。 */
    private val dirtyPaths = mutableSetOf<String>()

    init { load("/") }

    fun rememberScroll(key: String, mode: ViewMode, index: Int, offset: Int) {
        scrollPositions[key to mode] = ScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun scrollPosition(key: String, mode: ViewMode): ScrollPosition =
        scrollPositions[key to mode] ?: ScrollPosition(0, 0)

    // ---------------- 加载 ----------------

    fun load(path: String, refresh: Boolean = false, highlightFsId: Long? = null) {
        val s = _state.value
        _state.value = s.copy(
            path = path,
            crumbs = buildCrumbs(path),
            loading = !refresh,
            refreshing = refresh,
            error = "",
            empty = false,
            searching = false,
            keyword = "",
            selectMode = false,
            selected = emptySet(),
            highlightedFsId = highlightFsId,
        )
        viewModelScope.launch {
            val cur = _state.value
            val order = mapOrder(cur.sortBy)
            val desc = !cur.sortAsc
            val r = if (highlightFsId == null) {
                repo.list(path, order = order, desc = desc)
            } else {
                listUntilTarget(path, highlightFsId, order, desc)
            }
            when (r) {
                is FileRepository.Result.Ok -> {
                    val sorted = sortItems(r.data, cur.sortBy, cur.sortAsc)
                    snapshots[cur.path] = sorted
                    dirtyPaths.remove(cur.path)
                    _state.value = _state.value.copy(
                        items = sorted, loading = false, refreshing = false,
                        empty = sorted.isEmpty(),
                    )
                }
                is FileRepository.Result.Err -> _state.value = _state.value.copy(
                    loading = false, refreshing = false, error = r.message, authFailed = r.authFailed,
                )
            }
        }
    }

    /** 写操作后作废当前目录快照，保证下次加载拿到真实数据。 */
    private fun markDirty() {
        snapshots.remove(_state.value.path)
        dirtyPaths.add(_state.value.path)
    }

    fun refresh() {
        markDirty()
        val s = _state.value
        if (s.searching && s.keyword.isNotBlank()) search(s.keyword) else load(s.path, refresh = true)
    }

    fun enter(dir: PanFile) {
        if (!dir.isDirectory) return
        load(dir.path)
    }

    fun goUp() {
        val current = _state.value.path
        if (current == "/") return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        val cached = snapshots[parent]
        // 父目录没有改动过：直接用快照，避免每次后退都重新请求。
        if (cached != null && parent !in dirtyPaths) {
            _state.value = _state.value.copy(
                path = parent,
                crumbs = buildCrumbs(parent),
                items = cached,
                loading = false,
                refreshing = false,
                error = "",
                empty = cached.isEmpty(),
                searching = false,
                keyword = "",
                selectMode = false,
                selected = emptySet(),
                highlightedFsId = null,
            )
            return
        }
        load(parent)
    }

    fun jumpTo(path: String) = load(path)

    fun locate(file: PanFile) {
        val parent = file.path.substringBeforeLast('/').ifEmpty { "/" }
        load(parent, highlightFsId = file.fsId)
    }

    fun clearHighlight() {
        if (_state.value.highlightedFsId != null) {
            _state.value = _state.value.copy(highlightedFsId = null)
        }
    }

    // ---------------- 排序 / 展示 ----------------

    fun setSort(field: SortField) {
        val s = _state.value
        _state.value = s.copy(
            sortBy = field,
            items = sortItems(s.items, field, s.sortAsc),
            highlightedFsId = null,
        )
        session.sortPref = field.name
    }

    fun setSortAscending(ascending: Boolean) {
        val s = _state.value
        _state.value = s.copy(
            sortAsc = ascending,
            items = sortItems(s.items, s.sortBy, ascending),
            highlightedFsId = null,
        )
        session.sortAsc = ascending
    }

    fun setViewMode(mode: ViewMode) {
        if (_state.value.viewMode == mode) return
        _state.value = _state.value.copy(viewMode = mode, highlightedFsId = null)
        session.viewMode = mode.name
    }

    // ---------------- 搜索 ----------------

    fun setSearching(on: Boolean) {
        _state.value = _state.value.copy(
            searching = on,
            keyword = if (on) _state.value.keyword else "",
            highlightedFsId = null,
        )
        if (!on) load(_state.value.path)
    }

    fun search(key: String) {
        _state.value = _state.value.copy(
            keyword = key,
            searching = true,
            loading = true,
            error = "",
            highlightedFsId = null,
            searchSeq = _state.value.searchSeq + 1,
        )
        if (key.isBlank()) { load(_state.value.path); return }
        viewModelScope.launch {
            when (val r = repo.search(key, _state.value.path)) {
                is FileRepository.Result.Ok -> {
                    val s = _state.value
                    val files = r.data.filterNot { it.isDirectory }
                    _state.value = s.copy(
                        items = sortItems(files, s.sortBy, s.sortAsc),
                        loading = false, empty = files.isEmpty(),
                    )
                }
                is FileRepository.Result.Err -> _state.value = _state.value.copy(loading = false, error = r.message)
            }
        }
    }

    // ---------------- 多选 ----------------

    fun beginSelect(file: PanFile) {
        _state.value = _state.value.copy(
            selectMode = true,
            selected = setOf(file.fsId),
            highlightedFsId = null,
        )
    }

    fun toggleSelect(file: PanFile) {
        val s = _state.value
        val next = if (file.fsId in s.selected) s.selected - file.fsId else s.selected + file.fsId
        _state.value = s.copy(selected = next, selectMode = next.isNotEmpty(), highlightedFsId = null)
    }

    fun selectAll() {
        val s = _state.value
        val selected = if (s.allSelected) emptySet() else s.items.map { it.fsId }.toSet()
        _state.value = s.copy(selected = selected, selectMode = selected.isNotEmpty(), highlightedFsId = null)
    }

    fun cancelSelect() {
        _state.value = _state.value.copy(selectMode = false, selected = emptySet(), highlightedFsId = null)
    }

    // ---------------- 操作 ----------------

    fun openCreate() { _state.value = _state.value.copy(showCreate = true) }
    fun closeCreate() { _state.value = _state.value.copy(showCreate = false) }

    fun createFolder(name: String) {
        markDirty()
        closeCreate()
        viewModelScope.launch {
            when (val r = repo.createFolder(_state.value.path, name)) {
                is FileRepository.Result.Ok -> { toast("已创建"); refresh() }
                is FileRepository.Result.Err -> toast(r.message)
            }
        }
    }

    fun rename(file: PanFile, newName: String) {
        markDirty()
        viewModelScope.launch {
            when (val r = repo.rename(file.path, newName, file.isDirectory)) {
                is FileRepository.Result.Ok -> { toast("已重命名"); refresh() }
                is FileRepository.Result.Err -> toast(r.message)
            }
        }
    }

    fun delete(files: List<PanFile>) {
        markDirty()
        viewModelScope.launch {
            when (val r = repo.delete(files.map { it.path })) {
                is FileRepository.Result.Ok -> { toast("已删除"); cancelSelect(); refresh() }
                is FileRepository.Result.Err -> toast(r.message)
            }
        }
    }

    fun moveOrCopy(files: List<PanFile>, dest: String, copy: Boolean) {
        markDirty()
        viewModelScope.launch {
            when (val r = repo.moveOrCopy(files.map { it.path }, dest, copy)) {
                is FileRepository.Result.Ok -> { toast(if (copy) "已复制" else "已移动"); cancelSelect(); refresh() }
                is FileRepository.Result.Err -> toast(r.message)
            }
        }
    }

    // ---------------- 分享（有效期/提取码/二维码/编辑，网页版能力对齐） ----------------

    private val _share = MutableStateFlow(ShareUiState())
    val share: StateFlow<ShareUiState> = _share.asStateFlow()

    fun openShare(files: List<PanFile>) {
        _share.value = ShareUiState(files = files, fsIds = files.map { it.fsId })
    }

    fun closeShare() { _share.value = ShareUiState() }

    fun createShare(period: Int, pwd: String) {
        val s = _share.value
        if (s.files.isEmpty()) return
        _share.value = s.copy(creating = true, error = "")
        viewModelScope.launch {
            when (val r = repo.share(s.fsIds, period, pwd)) {
                is FileRepository.Result.Ok -> {
                    val d = r.data
                    _share.value = _share.value.copy(
                        creating = false, done = true,
                        shareId = d.shareId, link = d.link, pwd = d.pwd.ifBlank { pwd },
                        qrcodeUrl = d.qrcodeUrl.ifBlank {
                            if (d.shortUrl.isNotBlank())
                                "https://pan.baidu.com/api/wechat/qrcode?shorturl=${d.shortUrl}&clienttype=0&channel=chunlei&pwd=$pwd"
                            else ""
                        },
                    )
                }
                is FileRepository.Result.Err -> _share.value = _share.value.copy(creating = false, error = r.message)
            }
        }
    }

    fun modifyShare(period: Int, pwd: String) {
        val s = _share.value
        if (s.shareId <= 0) return
        _share.value = s.copy(creating = true, error = "")
        viewModelScope.launch {
            when (val r = repo.modifyShare(s.shareId, s.fsIds, period, pwd)) {
                is FileRepository.Result.Ok -> {
                    val d = r.data
                    val newLink = d.link.ifBlank { s.link }
                    val surl = d.shortUrl.ifBlank { newLink.substringAfterLast('/').removePrefix("s/1") }
                    _share.value = _share.value.copy(
                        creating = false, done = true,
                        link = newLink, pwd = d.pwd.ifBlank { pwd },
                        qrcodeUrl = if (surl.isNotBlank())
                            "https://pan.baidu.com/api/wechat/qrcode?shorturl=$surl&clienttype=0&channel=chunlei&pwd=$pwd"
                        else s.qrcodeUrl,
                    )
                }
                is FileRepository.Result.Err -> _share.value = _share.value.copy(creating = false, error = r.message)
            }
        }
    }

    // ---------------- 详情 ----------------

    private val _detail = MutableStateFlow<PanFile?>(null)
    val detail: StateFlow<PanFile?> = _detail.asStateFlow()
    fun openDetail(f: PanFile) { _detail.value = f }
    fun closeDetail() { _detail.value = null }

    // ---------------- 移动/复制目录选择 ----------------

    data class DirPickUiState(
        val visible: Boolean = false,
        val copy: Boolean = false,
        val files: List<PanFile> = emptyList(),
    )

    private val _dirPick = MutableStateFlow(DirPickUiState())
    val dirPick: StateFlow<DirPickUiState> = _dirPick.asStateFlow()

    fun openDirPicker(copy: Boolean, files: List<PanFile>) {
        if (files.isEmpty()) return
        _dirPick.value = DirPickUiState(true, copy, files)
    }

    fun closeDirPicker() { _dirPick.value = DirPickUiState() }

    fun confirmDirPicker(dest: String) {
        markDirty()
        val s = _dirPick.value
        if (!s.visible || s.files.isEmpty()) return
        viewModelScope.launch {
            when (val r = repo.moveOrCopy(s.files.map { it.path }, dest, s.copy)) {
                is FileRepository.Result.Ok -> {
                    toast(if (s.copy) "已复制" else "已移动")
                    closeDirPicker()
                    refresh()
                }
                is FileRepository.Result.Err -> toast(r.message)
            }
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = "") }

    private fun toast(msg: String) { _state.value = _state.value.copy(message = msg) }

    // ---------------- 内部 ----------------

    private fun buildCrumbs(path: String): List<Crumb> {
        val out = mutableListOf(Crumb("/", "全部文件"))
        if (path == "/") return out
        var acc = ""
        path.trim('/').split('/').forEach { seg ->
            if (seg.isNotBlank()) {
                acc += "/$seg"
                out.add(Crumb(acc, seg))
            }
        }
        return out
    }

    private suspend fun listUntilTarget(
        path: String,
        targetFsId: Long,
        order: String,
        desc: Boolean,
    ): FileRepository.Result<List<PanFile>> {
        val files = mutableListOf<PanFile>()
        var page = 1
        while (true) {
            when (val result = repo.list(path, page = page, num = 200, order = order, desc = desc)) {
                is FileRepository.Result.Err -> return result
                is FileRepository.Result.Ok -> {
                    files += result.data
                    if (result.data.any { it.fsId == targetFsId } || result.data.size < 200) {
                        return FileRepository.Result.Ok(files)
                    }
                    page++
                }
            }
        }
    }

    private fun mapOrder(f: SortField): String = when (f) {
        SortField.NAME -> "name"
        SortField.TIME -> "time"
        SortField.SIZE -> "size"
        SortField.TYPE -> "type"
    }

    /** 目录永远在前，其余按字段排序 */
    private fun sortItems(list: List<PanFile>, field: SortField, asc: Boolean): List<PanFile> {
        val dirs = list.filter { it.isDirectory }
        val files = list.filterNot { it.isDirectory }
        val cmp = when (field) {
            SortField.NAME -> compareBy<PanFile> { it.name.lowercase() }
            SortField.TIME -> compareBy { it.mtime }
            SortField.SIZE -> compareBy { it.size }
            SortField.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
        }
        val d = if (asc) dirs.sortedWith(cmp) else dirs.sortedWith(cmp.reversed())
        val f = if (asc) files.sortedWith(cmp) else files.sortedWith(cmp.reversed())
        return d + f
    }
}
