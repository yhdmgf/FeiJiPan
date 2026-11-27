package com.panclone.app.ui.screen.myshare

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.ShareRecord
import com.panclone.app.data.repo.FileRepository
import com.panclone.app.ui.screen.files.iconResFor
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import kotlinx.coroutines.launch

/**
 * 我的分享：布局参考 PanNative SharesActivity。
 * 每行 = 左文件类型图标 + 文件名/一行元信息 + 右竖排「复制链接 / 取消分享」胶囊按钮。
 * 长按进入多选，可批量取消。元信息不再显示到期时间。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyShareScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val repo = remember { FileRepository(App.instance.api) }

    var items by remember { mutableStateOf<List<ShareRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    var selectMode by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true; error = ""
        when (val r = repo.shareRecords()) {
            is FileRepository.Result.Ok -> items = r.data
            is FileRepository.Result.Err -> error = r.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    BackHandler {
        when {
            selectMode -> { selectMode = false; selected.clear() }
            else -> onBack()
        }
    }

    fun copy(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(ctx, "$label 已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun cancelShares(ids: List<Long>, successMsg: String) {
        if (ids.isEmpty()) return
        scope.launch {
            when (val r = repo.cancelShare(ids)) {
                is FileRepository.Result.Ok -> {
                    android.widget.Toast.makeText(ctx, successMsg, android.widget.Toast.LENGTH_SHORT).show()
                    selected.clear(); selectMode = false; load()
                }
                is FileRepository.Result.Err -> android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.textPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectMode) "已选择 ${selected.size} 项" else "我的分享",
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!selectMode) Text("长按可批量管理", color = colors.textTertiary, fontSize = 11.sp)
                }
                if (!selectMode) {
                    IconButton(onClick = { scope.launch { load() } }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = colors.accent)
                    }
                }
            }

            if (selectMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShareTextAction("全选", colors.accent) {
                        if (selected.size == items.size) selected.clear()
                        else { selected.clear(); selected.addAll(items.map { it.shareId }) }
                    }
                    ShareTextAction("取消分享", colors.danger) { confirmCancel = true }
                    Spacer(Modifier.weight(1f))
                    ShareTextAction("取消", colors.textSecondary) { selectMode = false; selected.clear() }
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
                        ShareTextAction("重试", colors.accent) { scope.launch { load() } }
                    }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("还没有分享", color = colors.textSecondary, fontSize = 15.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.shareId }) { _, item ->
                        val checked = item.shareId in selected
                        ShareCard(
                            item = item,
                            selecting = selectMode,
                            checked = checked,
                            onSelect = { toggle(selected, item.shareId) },
                            onLongClick = {
                                selectMode = true
                                if (item.shareId !in selected) selected.add(item.shareId)
                            },
                            onCopy = { copy(fullShareText(item), "链接和提取码") },
                            onCancel = {
                                selected.clear(); selected.add(item.shareId); confirmCancel = true
                            },
                        )
                    }
                    item { Spacer(Modifier.height(110.dp)) }
                }
            }
        }

        if (confirmCancel) {
            ShareConfirmDialog(
                title = "取消分享",
                text = "确定取消选中的 ${selected.size} 条分享？取消后链接将失效。",
                onCancel = { confirmCancel = false },
                onConfirm = {
                    val ids = selected.toList()
                    confirmCancel = false
                    cancelShares(ids, "已取消 ${ids.size} 条分享")
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareCard(
    item: ShareRecord,
    selecting: Boolean,
    checked: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    val icon = iconResFor(PanFile(name = item.fileName, category = item.typicalCategory))
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(if (checked) colors.accent.copy(alpha = 0.12f) else colors.cardBackground)
            .combinedClickable(
                onClick = { if (selecting) onSelect() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onSelect() },
                colors = CheckboxDefaults.colors(checkedColor = colors.accent),
            )
            Spacer(Modifier.width(4.dp))
        }
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.fileName.ifBlank { item.typicalPath },
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                shareMeta(item),
                color = colors.textTertiary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selecting) {
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                ShareMiniPill("复制链接", danger = false, onClick = onCopy)
                ShareMiniPill("取消分享", danger = true, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun ShareMiniPill(text: String, danger: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val fg = if (danger) colors.danger else colors.accent
    val bg = if (danger) colors.danger.copy(alpha = 0.10f) else colors.accent.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 11.sp, maxLines = 1)
    }
}

private fun fullShareText(item: ShareRecord): String =
    if (item.passwd.isBlank()) item.shortlink else "${item.shortlink} 提取码:${item.passwd}"

private fun shareMeta(item: ShareRecord): String = buildString {
    append(if (item.passwd.isNotBlank()) "提取码 ${item.passwd}" else "无提取码")
    append("  ·  ")
    append("${item.vCnt} 次浏览")
}

private fun toggle(list: androidx.compose.runtime.snapshots.SnapshotStateList<Long>, id: Long) {
    if (id in list) list.remove(id) else list.add(id)
}

@Composable
private fun ShareTextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 5.dp, vertical = 5.dp),
    )
}

@Composable
private fun ShareConfirmDialog(title: String, text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
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
                ShareTextAction("取消", colors.textSecondary, onCancel)
                Spacer(Modifier.width(16.dp))
                ShareTextAction("确定", colors.danger, onConfirm)
            }
        }
    }
}
