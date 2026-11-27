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

/** 目录栈的一层：path 存小飞机 folderId（根为空串），name 为展示名 */
data class Crumb(val path: String, val name: String)

data class ScrollPosition(val index: Int, val offset: Int)

data class FilesUiState(
    /** 当前目录 folderId（根为空串） */
    val path: String = "",
    val crumbs: List<Crumb> = listOf(Crumb("", "全部文件")),
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

/** 分享弹层状态：files 非空表示弹层打开 */
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

/**
 * 文件页 ViewModel（小飞机网盘版）。
 *
 * 导航基于小飞机 folderId 栈（小飞机没有路径概念）：
 * 进目录 push、后退 pop，面包屑按栈渲染。排序纯客户端（小飞机列表无排序参数）。
 */
class FilesViewModel : ViewModel() {
    private val repo = FileRepository(App.instance.feiji)
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
    /** 本次会话中被修改过的目录，用于判定能否复用快照。 */
    private val dirtyPaths = mutableSetOf<String>()

    /** 每页 60 条，最多拉 20 页（1200 项）避免极端大分享卡死 */
    private companion object {
        const val MAX_PAGES = 20
    }

    init { load("") }

    fun rememberScroll(key: String, mode: ViewMode, index: Int, offset: Int) {
        scrollPositions[key to mode] = ScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun scrollPosition(key: String, mode: ViewMode): ScrollPosition =
        scrollPositions[key to mode] ?: ScrollPosition(0, 0)

    // ---------------- 加载 ----------------

    /** 加载目录（folderId，根用空串）：循环翻页拉全，再客户端排序。 */
    fun load(folderId: String, refresh: Boolean = false, highlightFsId: Long? = null) {
        val s = _state.value
        _state.value = s.copy(
            path = folderId,
            crumbs = rebuildCrumbs(folderId),
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
            val all = mutableListOf<PanFile>()
            var page = 1
            while (page <= MAX_PAGES) {
                when (val r = repo.list(folderId, page)) {
                    is FileRepository.Result.Ok -> {
                        all += r.data
                        if (r.data.isEmpty()) break
                        page++
                    }
                    is FileRepository.Result.Err -> {
                        _state.value = _state.value.copy(
                            loading = false, refreshing = false, error = r.message, authFailed = r.authFailed,
                        )
                        return@launch
                    }
                }
            }
            if (highlightFsId != null && all.none { it.fsId == highlightFsId }) {
                // 目标在更后面：继续翻页直到找到或拉尽（locate 用）
                while (page <= MAX_PAGES) {
                    when (val r = repo.list(folderId, page)) {
                        is FileRepository.Result.Ok -> {
                            all += r.data
                            if (r.data.isEmpty() || r.data.any { it.fsId == highlightFsId }) break
                            page++
                        }
                        else -> break
                    }
                }
            }
            val sorted = sortItems(all, cur.sortBy, cur.sortAsc)
            snapshots[cur.path] = sorted
            dirtyPaths.remove(cur.path)
            _state.value = _state.value.copy(
                items = sorted, loading = false, refreshing = false,
                empty = sorted.isEmpty(),
            )
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
        // dir.path 存的是小飞机 folderId；先带上真实目录名入栈，再加载
        val crumbs = _state.value.crumbs
        val newCrumbs = if (crumbs.any { it.path == dir.path }) crumbs
        else crumbs + Crumb(dir.path, dir.name)
        _state.value = _state.value.copy(crumbs = newCrumbs)
        load(dir.path)
    }

    fun goUp() {
        val crumbs = _state.value.crumbs
        if (crumbs.size <= 1) return
        val parent = crumbs[crumbs.size - 2]
        val cached = snapshots[parent.path]
        // 父目录没有改动过：直接用快照，避免每次后退都重新请求。
        if (cached != null && parent.path !in dirtyPaths) {
            _state.value = _state.value.copy(
                path = parent.path,
                crumbs = crumbs.dropLast(1),
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
        load(parent.path)
    }

    fun jumpTo(folderId: String) = load(folderId)

    fun locate(file: PanFile) {
        // 小飞机没有父目录信息，跳转回当前目录并高亮
        load(_state.value.path, highlightFsId = file.fsId)
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

    // ---------------- 操作（小飞机不支持，统一 toast 提示） ----------------

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

    // ---------------- 分享（小飞机不支持创建分享，保留状态供 UI 复用） ----------------

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
                        qrcodeUrl = d.qrcodeUrl,
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
                    _share.value = _share.value.copy(
                        creating = false, done = true,
                        link = d.link.ifBlank { s.link }, pwd = d.pwd.ifBlank { pwd },
                        qrcodeUrl = d.qrcodeUrl.ifBlank { s.qrcodeUrl },
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

    // ---------------- 移动/复制目录选择（小飞机不支持，保留状态） ----------------

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

    /**
     * 重建面包屑。小飞机没有父目录信息，这里按当前 folderId 在目录栈中的位置回溯：
     * 根固定为「全部文件」；进入子目录时由 enter() 先在 crumbs 末尾追加，这里只做一致性裁剪。
     */
    private fun rebuildCrumbs(folderId: String): List<Crumb> {
        val root = Crumb("", "全部文件")
        val existing = _state.value.crumbs
        if (folderId.isEmpty()) return listOf(root)
        // 已在栈里（后退/跳转）：截断到该层
        val idx = existing.indexOfFirst { it.path == folderId }
        if (idx >= 0) return existing.take(idx + 1)
        // 新进入的子目录：追加（名称稍后由列表渲染，先占位）
        return existing + Crumb(folderId, folderId)
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
