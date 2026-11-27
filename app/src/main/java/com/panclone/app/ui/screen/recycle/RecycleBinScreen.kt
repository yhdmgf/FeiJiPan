package com.panclone.app.ui.screen.recycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.RecycleItem
import com.panclone.app.data.repo.FileRepository
import com.panclone.app.ui.components.VerifyDialog
import com.panclone.app.ui.screen.files.iconResFor
import com.panclone.app.ui.screen.files.isImageFile
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.ui.screen.preview.PreviewScreen
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import com.panclone.app.util.formatSize
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val recycleDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FileRepository(App.instance.api) }

    var items by remember { mutableStateOf<List<RecycleItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    var selectMode by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingVerify by remember { mutableStateOf<(() -> Unit)?>(null) }
    var previewFiles by remember { mutableStateOf<List<PanFile>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }

    suspend fun load() {
        loading = true; error = ""
        when (val r = repo.recycleList()) {
            is FileRepository.Result.Ok -> items = r.data.sortedByDescending { it.isDirectory }
            is FileRepository.Result.Err -> error = r.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    BackHandler {
        when {
            previewFiles.isNotEmpty() -> previewFiles = emptyList()
            selectMode -> { selectMode = false; selected.clear() }
            else -> onBack()
        }
    }

    fun runOp(action: suspend () -> FileRepository.Result<Unit>, successMsg: String, onDone: () -> Unit = {}) {
        scope.launch {
            val r = action()
            if (r is FileRepository.Result.Err && r.authFailed) {
                pendingVerify = {
                    scope.launch {
                        val retry = action()
                        if (retry is FileRepository.Result.Ok) {
                            android.widget.Toast.makeText(ctx, successMsg, android.widget.Toast.LENGTH_SHORT).show()
                            selected.clear(); selectMode = false; previewFiles = emptyList(); load(); onDone()
                        } else if (retry is FileRepository.Result.Err) {
                            android.widget.Toast.makeText(ctx, retry.message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else if (r is FileRepository.Result.Ok) {
                android.widget.Toast.makeText(ctx, successMsg, android.widget.Toast.LENGTH_SHORT).show()
                selected.clear(); selectMode = false; previewFiles = emptyList(); load(); onDone()
            } else if (r is FileRepository.Result.Err) {
                android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    if (previewFiles.isNotEmpty()) {
        Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
            PreviewScreen(
                files = previewFiles,
                initialIndex = previewIndex,
                onBack = { previewFiles = emptyList() },
                recycleMode = true,
                onRecycleRestore = { file ->
                    runOp({ repo.recycleRestore(listOf(file.fsId)) }, "已还原「${file.name}」")
                },
                onRecycleDelete = { file ->
                    runOp({ repo.recycleDelete(listOf(file.fsId)) }, "已彻底删除「${file.name}」")
                },
            )
            pendingVerify?.let { retry ->
                VerifyDialog(onDismiss = { pendingVerify = null }, onSuccess = {
                    pendingVerify = null
                    retry()
                })
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectMode) "已选择 ${selected.size} 项" else "回收站",
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!selectMode) {
                        Text("删除的文件将在这里保留一段时间", color = colors.textTertiary, fontSize = 11.sp)
                    }
                }
                if (!selectMode) {
                    IconButton(onClick = { scope.launch { load() } }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = colors.accent)
                    }
                    TextAction("清空", colors.danger) { confirmClear = true }
                }
            }

            if (selectMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextAction("全选", colors.accent) {
                        if (selected.size == items.size) selected.clear()
                        else { selected.clear(); selected.addAll(items.map { it.fsId }) }
                    }
                    TextAction("还原", colors.accent) {
                        if (selected.isNotEmpty()) runOp({ repo.recycleRestore(selected.toList()) }, "已还原 ${selected.size} 项")
                    }
                    TextAction("彻底删除", colors.danger) { confirmDelete = true }
                    Spacer(Modifier.weight(1f))
                    TextAction("取消", colors.textSecondary) { selectMode = false; selected.clear() }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp)
                }
                error.isNotBlank() && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = colors.danger, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        TextAction("重试", colors.accent) { scope.launch { load() } }
                    }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("回收站是空的", color = colors.textSecondary, fontSize = 15.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.fsId }) { _, item ->
                        val checked = item.fsId in selected
                        val file = item.toPanFile()
                        val thumb = thumbUrlOf(file)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.card))
                                .background(if (checked) colors.accent.copy(alpha = 0.10f) else colors.cardBackground)
                                .combinedClickable(
                                    onClick = {
                                        if (selectMode) toggle(selected, item.fsId)
                                        else if (!item.isDirectory && isImageFile(file) && thumb != null) {
                                            val images = items.filterNot { it.isDirectory }.map { it.toPanFile() }
                                                .filter { isImageFile(it) && thumbUrlOf(it) != null }
                                            previewFiles = images
                                            previewIndex = images.indexOfFirst { it.fsId == item.fsId }.coerceAtLeast(0)
                                        }
                                    },
                                    onLongClick = {
                                        selectMode = true
                                        if (item.fsId !in selected) selected.add(item.fsId)
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectMode) {
                                androidx.compose.material3.Checkbox(
                                    checked = checked,
                                    onCheckedChange = { toggle(selected, item.fsId) },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = colors.accent),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)), Alignment.Center) {
                                if (!item.isDirectory && isImageFile(file) && thumb != null) {
                                    AsyncImage(
                                        model = thumb,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)),
                                    )
                                } else {
                                    Image(
                                        painter = painterResource(iconResFor(file)),
                                        contentDescription = null,
                                        modifier = Modifier.size(38.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    wrapName(item.name, 20),
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (item.isDirectory) formatRecycleTime(item)
                                        else "${formatSize(item.size)}  ·  ${formatRecycleTime(item)}",
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        recycleExpireText(item),
                                        color = colors.accent,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (!selectMode) {
                                Spacer(Modifier.width(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RecycleMiniButton("还原", danger = false) {
                                        runOp({ repo.recycleRestore(listOf(item.fsId)) }, "已还原「${item.name}」")
                                    }
                                    RecycleMiniButton("删除", danger = true) { confirmDelete = true; selected.clear(); selected.add(item.fsId) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(110.dp)) }
                }
            }
        }

        if (confirmDelete) {
            SimpleConfirmDialog(
                title = "彻底删除",
                text = "确定彻底删除选中的 ${selected.size} 项？此操作不可恢复。",
                onCancel = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    if (selected.isNotEmpty()) runOp({ repo.recycleDelete(selected.toList()) }, "已彻底删除 ${selected.size} 项")
                },
            )
        }
        if (confirmClear) {
            SimpleConfirmDialog(
                title = "清空回收站",
                text = "确定清空回收站？所有文件将被彻底删除，不可恢复。",
                onCancel = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    runOp({ repo.recycleClear() }, "回收站已清空")
                },
            )
        }
        pendingVerify?.let { retry ->
            VerifyDialog(onDismiss = { pendingVerify = null }, onSuccess = {
                pendingVerify = null
                retry()
            })
        }
    }
}

private fun toggle(list: androidx.compose.runtime.snapshots.SnapshotStateList<Long>, id: Long) {
    if (id in list) list.remove(id) else list.add(id)
}

private fun RecycleItem.toPanFile() = PanFile(
    fsId = fsId,
    path = path,
    name = name,
    size = size,
    mtime = mtime,
    ctime = ctime,
    isDir = isDir,
    md5 = md5,
    category = category,
    thumbs = thumbs,
)

private fun formatRecycleTime(item: RecycleItem): String {
    val t = if (item.mtime > 0) item.mtime else item.ctime
    return if (t > 0) recycleDateFmt.format(Date(t * 1000)) else ""
}

private fun recycleExpireText(item: RecycleItem): String {
    if (item.leftTime <= 0) return ".已到期"
    val base = if (item.mtime > 0) item.mtime else item.ctime
    if (base <= 0) return ".剩余 ${item.leftTime} 天"
    return ".到期" + recycleDateFmt.format(Date((base + item.leftTime * 86400L) * 1000))
}

@Composable
private fun RecycleMiniButton(text: String, danger: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val fg = if (danger) colors.danger else colors.accent
    val bg = if (danger) colors.danger.copy(alpha = 0.12f) else colors.accent.copy(alpha = 0.12f)
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 12.sp, maxLines = 1)
    }
}

private fun wrapName(name: String, perLine: Int): String {
    if (name.length <= perLine) return name
    return buildString {
        for (i in name.indices step perLine) {
            if (i > 0) append('\n')
            append(name.substring(i, minOf(i + perLine, name.length)))
        }
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 5.dp, vertical = 5.dp),
    )
}

@Composable
private fun SimpleConfirmDialog(title: String, text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = LocalAppColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().background(colors.cardBackground, RoundedCornerShape(Radius.card)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 18.sp, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(text, fontSize = 14.sp, color = colors.textSecondary)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction("取消", colors.textSecondary, onCancel)
                Spacer(Modifier.width(16.dp))
                TextAction("确定", colors.danger, onConfirm)
            }
        }
    }
}
