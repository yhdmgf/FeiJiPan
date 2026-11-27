package com.panclone.app.ui.screen.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.panclone.app.data.model.PanFile
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import com.panclone.app.util.formatSize
import com.panclone.app.util.formatTime

/**
 * 文件页（移植 PanNative 规格）
 *
 * 自上而下：排序/展示方式行 → 路径(breadcrumb) → 列表或平铺网格 → 底部悬浮工具栏
 * 交互：点文件夹进入 / 点文件预览 / 更多或长按直接进入多选（小飞机网盘仅支持浏览/下载/预览）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    backdrop: LayerBackdrop,
    onOpenFile: (PanFile, List<PanFile>) -> Unit,
    onUpload: () -> Unit,
    onLogout: () -> Unit,
    onFabVisibility: (Boolean) -> Unit = {},
    /** 预览叠加时禁用本页返回拦截，让系统返回键一次退出预览且保留搜索状态 */
    previewing: Boolean = false,
    vm: FilesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val colors = LocalAppColors.current
    val ctx = LocalContext.current

    var showSort by remember { mutableStateOf(false) }

    // 两种展示方式各自保存滚动位置，并共用同一 backdrop 捕获层。
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var lastScrollPos by remember { mutableStateOf(0L) }
    LaunchedEffect(state.viewMode) {
        lastScrollPos = 0L
        val flow = if (state.viewMode == ViewMode.LIST) {
            snapshotFlow {
                listState.firstVisibleItemIndex * 1_000_000L + listState.firstVisibleItemScrollOffset
            }
        } else {
            snapshotFlow {
                gridState.firstVisibleItemIndex * 1_000_000L + gridState.firstVisibleItemScrollOffset
            }
        }
        flow.collect { pos ->
            val canScroll = if (state.viewMode == ViewMode.LIST) {
                listState.canScrollForward || listState.canScrollBackward
            } else {
                gridState.canScrollForward || gridState.canScrollBackward
            }
            if (state.path != "/" && !canScroll) {
                // 子目录文件少、不能滚动：强制显示，不让它消失
                onFabVisibility(true)
                lastScrollPos = pos
                return@collect
            }
            val delta = pos - lastScrollPos
            if (lastScrollPos == 0L) {
                // 首次只记录位置，不触发显隐
            } else if (kotlin.math.abs(delta) > 40) {
                if (state.path == "/") {
                    onFabVisibility(delta < 0)
                } else {
                    onFabVisibility(delta > 0)
                }
            }
            lastScrollPos = pos
        }
    }

    val listKey = if (state.searching) "search_${state.searchSeq}" else state.path

    fun rememberScrollPosition() {
        // 搜索结果不记录滚动位置（下次搜索永远从顶部）
        if (state.searching) return
        if (state.viewMode == ViewMode.LIST) {
            vm.rememberScroll(
                state.path,
                state.viewMode,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        } else {
            vm.rememberScroll(
                state.path,
                state.viewMode,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }
    }

    fun openItem(file: PanFile) {
        vm.clearHighlight()
        rememberScrollPosition()
        if (file.isDirectory) vm.enter(file) else onOpenFile(file, state.items)
    }

    // 子目录内悬浮按钮常显；回到根目录恢复按滚动方向显隐。
    LaunchedEffect(state.path, state.items.size, state.viewMode) {
        val canScroll = if (state.viewMode == ViewMode.LIST) {
            listState.canScrollForward || listState.canScrollBackward
        } else {
            gridState.canScrollForward || gridState.canScrollBackward
        }
        if (state.path != "/") {
            onFabVisibility(true)  // 子目录默认显示；文件少不能滚动时常显
        } else {
            onFabVisibility(false)  // 根目录按原滚动逻辑
        }
    }

    // 列表身份变化（进目录/返回/面包屑/新搜索）时，首帧定位到保存位置；搜索永远顶部。
    // 用LaunchedEffect而非动画scroll，数据加载与定位同帧完成，视觉上无滚动。
    LaunchedEffect(listKey, state.loading) {
        if (state.loading || state.items.isEmpty()) return@LaunchedEffect
        val target = if (state.searching) {
            ScrollPosition(0, 0)
        } else {
            vm.scrollPosition(listKey, state.viewMode)
        }
        if (state.viewMode == ViewMode.LIST) {
            listState.scrollToItem(target.index.coerceAtMost(state.items.size), target.offset)
        } else {
            gridState.scrollToItem(target.index.coerceAtMost(state.items.size), target.offset)
        }
        lastScrollPos = 0L
    }

    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            Toast.makeText(ctx, state.message, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    // 每完成一个上传任务就刷新当前目录（跳过 collect 首值，避免预览返回时误刷新）
    LaunchedEffect(Unit) {
        var lastSeen = -1
        com.panclone.app.App.instance.transfers.uploadsChanged.collect { n ->
            if (lastSeen >= 0 && n != lastSeen) vm.refresh()
            lastSeen = n
        }
    }

    // 返回（PanNative 逻辑）：多选→取消；子目录→上级
    BackHandler(enabled = !previewing && (showSort || state.selectMode || state.path != "/")) {
        when {
            showSort -> showSort = false
            state.selectMode -> vm.cancelSelect()
            state.path != "/" -> { rememberScrollPosition(); vm.goUp() }
        }
    }

    // 下拉刷新：任意路径、任意滚动位置（只要已到顶部）都能触发，只刷新当前目录。

    Box(Modifier.fillMaxSize()) {

        // ===== 捕获层：列表铺满全屏（顶部留给悬浮栏），滑动时从顶栏下方穿过 =====
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.pageBackground)
,
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp)
                }
                state.error.isNotBlank() -> ErrorView(state.error) { vm.refresh() }
                state.empty -> Box(Modifier.fillMaxSize()) {
                    EmptyView(
                        searching = state.searching,
                        keyword = state.keyword,
                        onRefresh = { vm.refresh() },
                    )
                }
                state.viewMode == ViewMode.LIST -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 3.dp),
                    state = listState,
                ) {
                    item { Spacer(Modifier.height(120.dp)) }
                    itemsIndexed(state.items, key = { _, file -> file.fsId }) { index, file ->
                        FileRow(
                            file = file,
                            selected = file.fsId in state.selected,
                            selecting = state.selectMode,
                            highlighted = file.fsId == state.highlightedFsId,
                            isFirst = index == 0,
                            isLast = index == state.items.lastIndex,
                            onClick = { if (state.selectMode) vm.toggleSelect(file) else openItem(file) },
                            onLongClick = { if (!state.selectMode) vm.beginSelect(file) },
                            onMore = { vm.beginSelect(file) },
                        )
                    }
                    item { Spacer(Modifier.height(160.dp)) }
                }
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) { Spacer(Modifier.height(120.dp)) }
                    items(state.items, key = { it.fsId }) { f ->
                        FileGridCell(
                            file = f,
                            selected = f.fsId in state.selected,
                            highlighted = f.fsId == state.highlightedFsId,
                            onClick = { if (state.selectMode) vm.toggleSelect(f) else openItem(f) },
                            onLongClick = { if (!state.selectMode) vm.beginSelect(f) },
                        )
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) { Spacer(Modifier.height(160.dp)) }
                }
            }
        }

        // ===== 下拉刷新指示器：跟随下拉距离缩放与旋转 =====
        // ===== 悬浮顶栏：与底栏同款毛玻璃，延伸进状态栏 =====
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            com.panclone.app.ui.components.TopFadeBlur(
                backdrop = backdrop,
                modifier = Modifier.matchParentSize(),
            )
            if (state.selectMode) {
                SelectionTopBar(
                    allSelected = state.allSelected,
                    count = state.selected.size,
                    onCancel = { vm.cancelSelect() },
                    onSelectAll = { vm.selectAll() },
                )
            } else {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                // 排序 + 展示方式（小飞机网盘不支持搜索）
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    // 排序
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { showSort = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Sort,
                            contentDescription = "排序与展示方式",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // 路径面包屑
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.crumbs.forEachIndexed { i, c ->
                        if (i > 0) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = colors.textTertiary, modifier = Modifier.size(14.dp),
                            )
                        }
                        val last = i == state.crumbs.lastIndex
                        Text(
                            c.name,
                            fontSize = 13.sp,
                            color = if (last) colors.textPrimary else colors.accent,
                            fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { rememberScrollPosition(); vm.jumpTo(c.path) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            }
        }

        // ===== 多选工具栏 =====
        if (state.selectMode) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                SelectToolbar(
                    count = state.selected.size,
                    onDownload = {
                        downloadAll(ctx, state.selectedFiles)
                        vm.cancelSelect()
                    },
                    onDetail = { state.selectedFiles.singleOrNull()?.let { vm.openDetail(it) } },
                    backdrop = backdrop,
                )
            }
        }

        if (showSort) {
            SortPopup(
                backdrop = backdrop,
                sortBy = state.sortBy,
                ascending = state.sortAsc,
                viewMode = state.viewMode,
                onSort = vm::setSort,
                onDirection = vm::setSortAscending,
                onViewMode = { mode ->
                    rememberScrollPosition()
                    vm.setViewMode(mode)
                },
                onDismiss = { showSort = false },
            )
        }
    } // 外层 Box 结束

    // 小飞机网盘不支持删除/分享/重命名/移动/复制，对应弹层入口已全部移除。

    val detailFile by vm.detail.collectAsState()
    detailFile?.let { df ->
        FileDetailDialog(file = df, thumbUrl = thumbUrlOf(df), onDismiss = { vm.closeDetail() })
    }
}

// ==================== 123 网盘式排序面板 ====================

@Composable
private fun SortPopup(
    backdrop: Backdrop,
    sortBy: SortField,
    ascending: Boolean,
    viewMode: ViewMode,
    onSort: (SortField) -> Unit,
    onDirection: (Boolean) -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(16.dp)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 56.dp, end = 12.dp)
                .width(178.dp)
                .clip(cardShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            com.panclone.app.ui.components.TopFadeBlur(
                backdrop = backdrop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        if (colors.isDark) Color(0xFF171B22).copy(alpha = 0.62f)
                        else Color.White.copy(alpha = 0.58f),
                    )
                    .then(
                        if (colors.isDark) Modifier
                        else Modifier.border(1.dp, Color(0x1A0F172A), cardShape),
                    ),
            )
            Column(Modifier.padding(12.dp)) {
                SortSectionTitle("排序方式")
                listOf(SortField.NAME, SortField.SIZE, SortField.TIME, SortField.TYPE).forEach { field ->
                    SortFieldRow(
                        label = field.label,
                        selected = sortBy == field,
                        onClick = { onSort(field) },
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortSegment(
                        text = "升序 ↑",
                        selected = ascending,
                        onClick = { onDirection(true) },
                        modifier = Modifier.weight(1f),
                    )
                    SortSegment(
                        text = "降序 ↓",
                        selected = !ascending,
                        onClick = { onDirection(false) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SortSectionTitle("展示方式", topPadding = 12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortSegment(
                        text = "列表",
                        selected = viewMode == ViewMode.LIST,
                        onClick = { onViewMode(ViewMode.LIST) },
                        modifier = Modifier.weight(1f),
                    )
                    SortSegment(
                        text = "平铺",
                        selected = viewMode == ViewMode.GRID,
                        onClick = { onViewMode(ViewMode.GRID) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SortSectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 2.dp) {
    val colors = LocalAppColors.current
    Text(
        text,
        color = colors.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = topPadding, end = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SortFieldRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clip(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) colors.accent else colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = colors.accent, fontSize = 14.sp)
    }
}

@Composable
private fun SortSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, colors.separator.copy(alpha = 0.45f), shape),
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color.White else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ==================== 列表 / 平铺单元 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: PanFile,
    selected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 全行统一圆角：按压/长按的半透明高亮必须是圆角，中间行不能露直角
    val shape = RoundedCornerShape(Radius.card)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 2.dp)
            .height(60.dp)
            .clip(shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(
                when {
                    highlighted -> colors.danger.copy(alpha = 0.18f)
                    selected -> colors.accent.copy(alpha = 0.12f)
                    // 列表行必须有不透明底：否则行间露出页面背景，顶栏/FAB 采样时几乎没有内容可模糊。
                    else -> colors.cardBackground
                },
            )
            .background(if (pressed) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (highlighted) Modifier.border(2.dp, colors.danger, shape) else Modifier,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = thumbUrlOf(file)
        if (thumb != null && (isImageFile(file) || isVideoFile(file))) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
            )
        } else {
            Icon(
                painter = painterResource(iconResFor(file)),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = Color.Unspecified,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                fontSize = 16.sp,
                color = if (highlighted) colors.danger else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.isDirectory) formatTime(file.mtime)
                else "${formatSize(file.size)} · ${formatTime(file.mtime)}",
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        if (selecting) {
            CheckCircle(selected)
        } else {
            Box(
                Modifier.size(30.dp).clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    file: PanFile,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(
                when {
                    highlighted -> colors.danger.copy(alpha = 0.18f)
                    selected -> colors.accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .background(if (pressed) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (highlighted) Modifier.border(2.dp, colors.danger, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val thumb = thumbUrlOf(file)
            if (thumb != null && (isImageFile(file) || isVideoFile(file))) {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Icon(
                    painter = painterResource(id = iconResFor(file)),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = Color.Unspecified,
                )
            }
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd)) { CheckCircle(true) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            file.name, fontSize = 11.sp,
            color = if (highlighted) colors.danger else colors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
    }
}

@Composable
private fun CheckCircle(selected: Boolean) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/** 多选顶栏：取消 / 已选数量 / 全选。 */
@Composable
private fun SelectionTopBar(
    allSelected: Boolean,
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "取消",
            color = colors.accent,
            fontSize = 15.sp,
            modifier = Modifier
                .width(88.dp)
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
        Text(
            "已选中${count}个文件",
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (allSelected) "取消全选" else "全选",
            color = colors.accent,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(88.dp)
                .clickable(onClick = onSelectAll)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

private data class SelectAction(
    val label: String,
    @androidx.annotation.DrawableRes val iconRes: Int,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** 小飞机网盘多选操作区：仅保留下载与文件详情（不支持分享/删除/重命名/移动/复制）。 */
@Composable
private fun SelectToolbar(
    count: Int,
    onDownload: () -> Unit,
    onDetail: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = LocalAppColors.current
    val single = count == 1
    val actions = buildList {
        add(SelectAction("下载", com.panclone.app.R.drawable.act_download, onClick = onDownload))
        if (single) add(SelectAction("文件详情", com.panclone.app.R.drawable.act_detail, onClick = onDetail))
    }

    Box(Modifier.fillMaxWidth()) {
        com.panclone.app.ui.components.TopFadeBlur(
            backdrop = backdrop,
            modifier = Modifier.matchParentSize(),
        )
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = imeBottom)
                .padding(vertical = 8.dp),
        ) {
            actions.chunked(5).forEach { rowActions ->
                Row(Modifier.fillMaxWidth()) {
                    rowActions.forEach { action -> BarAction(action) }
                    repeat(5 - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarAction(action: SelectAction) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) colors.press else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = action.onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = action.label,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            action.label,
            fontSize = 11.sp,
            color = if (action.danger) colors.danger else colors.textPrimary,
            maxLines = 1,
        )
    }
}

// ==================== 空态 / 错误 ====================

@Composable
private fun EmptyView(
    searching: Boolean,
    keyword: String,
    onRefresh: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (searching && keyword.isNotBlank()) {
            Text("没有找到「$keyword」", fontSize = 16.sp, color = colors.textSecondary)
            return@Column
        }
        Icon(
            painter = painterResource(com.panclone.app.R.drawable.fitype_icon_tsbg_folder),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(78.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("暂无文件", fontSize = 13.sp, color = colors.textSecondary)
        Spacer(Modifier.height(24.dp))
        Text(
            "刷新",
            color = colors.accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg, color = colors.danger, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text("重试", color = colors.accent, modifier = Modifier.clickable(onClick = onRetry))
    }
}

// ==================== 工具函数 ====================

fun iconResFor(file: PanFile): Int = when {
    file.isDirectory -> com.panclone.app.R.drawable.fitype_icon_tsbg_folder
    file.name.hasExt("pdf") -> com.panclone.app.R.drawable.fitype_icon_tsbg_pdf
    file.name.hasExt("doc", "docx") -> com.panclone.app.R.drawable.fitype_icon_tsbg_word
    file.name.hasExt("ppt", "pptx") -> com.panclone.app.R.drawable.fitype_icon_tsbg_ppt
    file.name.hasExt("xls", "xlsx", "csv") -> com.panclone.app.R.drawable.fitype_icon_tsbg_number
    file.name.hasExt("txt", "md", "log") -> com.panclone.app.R.drawable.fitype_icon_tsbg_txt
    file.name.hasExt("html", "htm") -> com.panclone.app.R.drawable.fitype_icon_tsbg_html
    file.name.hasExt("epub") -> com.panclone.app.R.drawable.fitype_icon_tsbg_epub
    file.name.hasExt("zip", "rar", "7z", "tar", "gz") -> com.panclone.app.R.drawable.fitype_icon_tsbg_archive
    file.name.hasExt("apk") -> com.panclone.app.R.drawable.fitype_icon_tsbg_apk
    file.name.hasExt("bt", "torrent") -> com.panclone.app.R.drawable.fitype_icon_tsbg_bt
    file.name.hasExt("vcf") -> com.panclone.app.R.drawable.fitype_icon_tsbg_vcf
    file.name.hasExt("vsd", "vsdx") -> com.panclone.app.R.drawable.fitype_icon_tsbg_vsd
    file.category == 4 || file.name.hasExt("mp3", "flac", "aac", "wav", "m4a", "ogg", "ape", "opus", "wma") ->
        com.panclone.app.R.drawable.fitype_icon_tsbg_music
    file.category == 3 || file.name.hasExt("mp4", "mkv", "avi", "mov", "flv", "ts", "webm", "m4v", "3gp", "rmvb", "wmv") ->
        com.panclone.app.R.drawable.fitype_icon_tsbg_pand
    file.category == 2 || file.name.hasExt("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") ->
        com.panclone.app.R.drawable.fitype_icon_tsbg_pand
    else -> com.panclone.app.R.drawable.fitype_icon_tsbg_other
}

fun String.hasExt(vararg exts: String): Boolean =
    substringAfterLast('.', "").lowercase() in exts

internal fun isImageFile(file: PanFile): Boolean =
    !file.isDirectory && (file.category == 2 || file.name.hasExt("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"))

internal fun isVideoFile(file: PanFile): Boolean =
    !file.isDirectory && (file.category == 3 || file.name.hasExt("mp4", "mkv", "avi", "mov", "flv", "ts", "webm", "m4v", "3gp", "rmvb", "wmv"))

internal fun thumbUrlOf(file: PanFile): String? =
    file.thumbs?.let { listOf(it.url3, it.url2, it.url1).firstOrNull { u -> u.isNotBlank() } }

internal fun downloadAll(ctx: android.content.Context, files: List<PanFile>) {
    // 下载目录在共享存储（/storage/emulated/0/BaiduNetdisk），需要「所有文件访问」权限
    if (!android.os.Environment.isExternalStorageManager()) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:com.panclone.app"),
                )
            )
        }
        Toast.makeText(ctx, "请先授予「所有文件访问」权限，再回到 App 下载", Toast.LENGTH_LONG).show()
        return
    }
    val app = ctx.applicationContext as com.panclone.app.App
    val dir = app.session.downloadDir.ifBlank {
        java.io.File(ctx.getExternalFilesDir(null), "downloads").absolutePath
    }
    val targets = files.filterNot { it.isDirectory }
    targets.forEach { f ->
        val thumb = f.thumbs?.url3?.ifBlank { null }
            ?: f.thumbs?.url2?.ifBlank { null }
            ?: f.thumbs?.url1?.ifBlank { null }
            ?: ""
        val t = app.transfers.addDownload(f.name, f.path, f.fsId, f.size, java.io.File(dir), thumb)
        app.transfers.start(t.id)
    }
    if (targets.isNotEmpty()) Toast.makeText(ctx, "已加入下载队列 ${targets.size} 项", Toast.LENGTH_SHORT).show()
}

/** 下拉刷新：触发距离与保持高度（px）。 */
