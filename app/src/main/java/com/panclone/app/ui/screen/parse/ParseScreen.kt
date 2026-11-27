package com.panclone.app.ui.screen.parse

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panclone.app.data.net.FeiJiPanFile
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import com.panclone.app.util.formatSize

/**
 * 解析页：输入小飞机分享链接 + 提取码 → 解析分享文件列表 → 点文件取签名直链 → 二次点「下载」。
 * 玻璃拟态风格，沿用 FilesScreen / SettingsScreen 的卡片、圆角与颜色 token。
 */
@Composable
fun ParseScreen(
    onBack: () -> Unit = {},
    vm: ParseViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val colors = LocalAppColors.current
    val ctx = LocalContext.current

    // 子目录内拦截返回键回到上级，根目录交给外层退出本页
    BackHandler(enabled = state.folderStack.isNotEmpty()) { vm.goUp() }

    androidx.compose.runtime.LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            Toast.makeText(ctx, state.message, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // ===== 顶部：返回 + 标题 =====
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.cardSecondary)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "解析",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }

        // ===== 输入区（玻璃卡片） =====
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Radius.card))
                .background(colors.cardBackground)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                placeholder = { Text("粘贴小飞机分享链接", fontSize = 14.sp, color = colors.textTertiary) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Link, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                },
                shape = RoundedCornerShape(Radius.chip),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.separator,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = vm::onCodeChange,
                    placeholder = { Text("提取码", fontSize = 14.sp, color = colors.textTertiary) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.chip),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.separator,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .height(56.dp)
                        .width(96.dp)
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(colors.accent)
                        .clickable(enabled = !state.loading) { vm.parse() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text("解析", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }

        // ===== 面包屑 / 当前路径 =====
        if (state.files.isNotEmpty() || state.folderStack.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.folderStack.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.cardSecondary)
                            .clickable { vm.goUp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "返回上级",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                BreadcrumbText(state)
            }
        }

        // ===== 结果列表 =====
        when {
            state.loading && state.files.isEmpty() -> {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp)
                }
            }
            state.files.isEmpty() -> {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.shareId.isBlank()) "粘贴链接后点「解析」" else "目录为空",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                }
            }
            else -> {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                ) {
                    items(state.files, key = { it.id }) { file ->
                        ParseFileRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) vm.enterFolder(file) else vm.selectFile(file)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
        }
    }

    // ===== 下载卡片：显示签名链接 + 文件名 + 下载按钮 =====
    if (state.showDownloadSheet) {
        DownloadSheet(
            fileName = state.selectedFile?.name ?: "",
            fileSize = state.selectedFile?.sizeBytes ?: 0L,
            downloadUrl = state.downloadUrl,
            onDismiss = { vm.dismissDownloadSheet() },
            onConfirm = { vm.confirmDownload() },
        )
    }
}

@Composable
private fun BreadcrumbText(state: ParseUiState) {
    val colors = LocalAppColors.current
    val crumbs = buildList {
        add("分享根目录")
        state.folderStack.forEach { add(it.name) }
    }
    Text(
        crumbs.joinToString("  /  "),
        fontSize = 13.sp,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ParseFileRow(
    file: FeiJiPanFile,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .background(colors.cardBackground)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (file.isDirectory) colors.accent else colors.textTertiary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                fontSize = 15.sp,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!file.isDirectory) {
                Text(
                    if (file.modifiedAt.isNotEmpty()) "${formatSize(file.sizeBytes)} · ${file.modifiedAt}"
                    else formatSize(file.sizeBytes),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (file.isDirectory) {
            Text("进入", fontSize = 12.sp, color = colors.accent)
        }
    }
}

/** 下载卡片：二次确认下载，显示签名直链，点「下载」才真正加入任务。 */
@Composable
private fun DownloadSheet(
    fileName: String,
    fileSize: Long,
    downloadUrl: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(colors.cardBackground)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.separator),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                fileName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fileSize > 0) {
                Spacer(Modifier.height(4.dp))
                Text(formatSize(fileSize), fontSize = 12.sp, color = colors.textSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "下载链接",
                fontSize = 12.sp,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                downloadUrl,
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.chip))
                    .background(colors.cardSecondary)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(colors.cardSecondary)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", fontSize = 15.sp, color = colors.textPrimary)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(colors.accent)
                        .clickable { onConfirm() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("下载", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}
