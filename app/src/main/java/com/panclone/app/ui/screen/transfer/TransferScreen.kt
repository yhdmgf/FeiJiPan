package com.panclone.app.ui.screen.transfer

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panclone.app.App
import com.panclone.app.data.model.TransferKind
import com.panclone.app.data.model.TransferState
import coil3.compose.AsyncImage
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.TransferTask
import com.panclone.app.ui.screen.files.iconResFor
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.util.formatSize
import kotlinx.coroutines.launch

/**
 * 传输页（移植 PanNative 规格）
 *
 * 顶部药丸分段控制器（下载 / 上传），白底指示器跟随位移；
 * 列表分「进行中 / 已完成」两组；卡片圆角 22，进度 3dp 渐变填充。
 */
@Composable
fun TransferScreen() {
    val colors = LocalAppColors.current
    val all by App.instance.transfers.tasks.collectAsState()
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    val tabs = listOf("下载", "上传")

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- 分段控制器 ----
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 12.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.cardSecondary)
                .padding(3.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, label ->
                    val on = pager.currentPage == i
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = 14.sp,
                            color = if (on) colors.accent else colors.textSecondary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // ---- 两页横向滑动 ----
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { page ->
            val kind = if (page == 0) TransferKind.DOWNLOAD else TransferKind.UPLOAD
            val list = all.filter { it.kind == kind }
            TransferList(list)
        }
    }
}

@Composable
private fun TransferList(tasks: List<TransferTask>) {
    val colors = LocalAppColors.current
    val active = tasks.filter { it.state != TransferState.DONE }
    val done = tasks.filter { it.state == TransferState.DONE }

    if (tasks.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("暂无任务", fontSize = 16.sp, color = colors.textSecondary)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (active.isNotEmpty()) {
            item { GroupHead("进行中", active.size, active.count { it.state == TransferState.PAUSED }) }
            items(active, key = { it.id }) { TaskCard(it) }
        }
        if (done.isNotEmpty()) {
            // 已完成组内：最后完成的在最上面
            val doneSorted = done.sortedByDescending { it.doneAt }
            item { GroupHead("已完成", doneSorted.size, 0) }
            items(doneSorted, key = { it.id }) { TaskCard(it) }
        }
        item { Spacer(Modifier.height(130.dp)) }
    }
}

@Composable
private fun GroupHead(title: String, count: Int, paused: Int) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title（$count）", fontSize = 13.sp, color = colors.textSecondary)
        if (paused > 0) {
            Text("  · $paused 个已暂停", fontSize = 12.sp, color = colors.textTertiary)
        }
    }
}

@Composable
private fun TaskCard(task: TransferTask) {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.cardBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaskIcon(task)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.name,
                    fontSize = 15.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    statusText(task),
                    fontSize = 12.sp,
                    color = if (task.state == TransferState.FAILED) colors.danger else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (task.state) {
                TransferState.RUNNING -> CircleBtn(Icons.Default.Pause) {
                    App.instance.transfers.pause(task.id)
                }
                TransferState.PAUSED, TransferState.FAILED -> CircleBtn(Icons.Default.PlayArrow) {
                    App.instance.transfers.resume(task.id)
                }
                else -> Unit
            }
            if (task.kind == TransferKind.DOWNLOAD && task.state == TransferState.DONE) {
                Spacer(Modifier.width(6.dp))
                CircleBtn(Icons.Default.OpenInNew) { openDownloaded(ctx, task.localPath) }
            }
            Spacer(Modifier.width(6.dp))
            CircleBtn(Icons.Default.Close, tint = colors.textTertiary) {
                App.instance.transfers.remove(task.id)
            }
        }
        if (task.state != TransferState.DONE) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.separator.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(colors.accent, colors.accent.copy(alpha = 0.75f))
                            )
                        ),
                )
            }
        }
    }
}

/** 任务缩略图：图片类显示真实预览（下载用云缩略图，上传用本地文件），其它显示类型图标 */
@Composable
private fun TaskIcon(task: TransferTask) {
    val colors = LocalAppColors.current
    val isImage = task.name.substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    if (isImage && task.kind == TransferKind.DOWNLOAD && task.thumb.isNotBlank()) {
        AsyncImage(
            model = task.thumb,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    if (isImage && task.kind == TransferKind.UPLOAD && java.io.File(task.localPath).exists()) {
        AsyncImage(
            model = java.io.File(task.localPath),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    Icon(
        painter = painterResource(id = iconResFor(PanFile(name = task.name))),
        contentDescription = null,
        modifier = Modifier.size(36.dp),
        tint = androidx.compose.ui.graphics.Color.Unspecified,
    )
}

/** 已完成的下载：APK 先确认安装来源权限，其它文件交给系统打开。 */
private fun openDownloaded(ctx: android.content.Context, path: String) {
    runCatching {
        val file = java.io.File(path)
        if (!file.exists()) {
            Toast.makeText(ctx, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val isApk = file.extension.equals("apk", ignoreCase = true)
        if (isApk && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${ctx.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(ctx, "请允许此应用安装未知来源应用，然后重新点击打开", Toast.LENGTH_LONG).show()
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file,
        )
        val mime = if (isApk) {
            "application/vnd.android.package-archive"
        } else {
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
        intent.clipData = android.content.ClipData.newRawUri(file.name, uri)
        intent.addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        )

        val handlers = ctx.packageManager.queryIntentActivities(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (handlers.isEmpty()) error("没有可处理该文件的系统应用")
        handlers.forEach { info ->
            ctx.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        ctx.startActivity(intent)
    }.onFailure {
        Toast.makeText(ctx, "打开失败：${it.message ?: "没有可用应用"}", Toast.LENGTH_LONG).show()
    }
}

private fun statusText(t: TransferTask): String = when (t.state) {
    TransferState.PENDING -> "等待中"
    TransferState.RUNNING -> "${formatSize(t.done)} / ${formatSize(t.size)}"
    TransferState.PAUSED -> "已暂停 · ${formatSize(t.done)} / ${formatSize(t.size)}"
    TransferState.DONE -> "已完成 · ${formatSize(t.size)}"
    TransferState.FAILED -> "失败：${t.error}"
}

@Composable
private fun CircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.cardSecondary)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: colors.accent, modifier = Modifier.size(17.dp))
    }
}
