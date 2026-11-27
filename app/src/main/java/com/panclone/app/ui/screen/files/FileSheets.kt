@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.panclone.app.ui.screen.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.repo.FileRepository
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.util.formatSize
import com.panclone.app.util.formatTime

/**
 * 统一底部弹层容器（Material3 ModalBottomSheet，开源库 androidx.compose.material3）。
 * 全部弹窗（文件详情/目录选择）共用，保证风格一致 + 底部滑入动画。
 */
@Composable
fun PanSheet(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
        content = content,
    )
}

/** 文件详情弹窗 */
@Composable
fun FileDetailDialog(
    file: PanFile,
    thumbUrl: String?,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    PanSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!file.isDirectory && isImageFile(file) && thumbUrl != null) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = iconResFor(file)),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Unspecified,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                file.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val type = if (file.isDirectory) "文件夹" else file.name.substringAfterLast('.', "").uppercase().ifBlank { "文件" }
            val rows = listOf(
                "类型" to type,
                "大小" to (if (file.isDirectory) "-" else formatSize(file.size)),
                "修改时间" to formatTime(file.mtime),
                "位置" to file.path.substringBeforeLast('/').ifEmpty { "/" },
                "MD5" to file.md5.ifBlank { "-" },
                "fs_id" to file.fsId.toString(),
            )
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                    Text(
                        v,
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.cardSecondary)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.accent)
            }
        }
    }
}

/** 目录选择器：仅由预览页的移动/复制入口使用；文件浏览页不支持移动/复制，入口已隐藏。 */
@Composable
fun DirPickerDialog(
    title: String,
    count: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val repo = remember { FileRepository(App.instance.feiji) }
    var path by remember { mutableStateOf("/") }
    var dirs by remember { mutableStateOf<List<PanFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(path, reload) {
        loading = true
        val r = repo.list(path)
        dirs = (r as? FileRepository.Result.Ok)?.data
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        loading = false
    }

    PanSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(),
        ) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (path != "/") path = path.substringBeforeLast('/').ifEmpty { "/" } else onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        tint = colors.textPrimary,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(
                        "已选 $count 项 · 当前位置 $path",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { creating = !creating; newName = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = "新建文件夹",
                        modifier = Modifier.size(20.dp),
                        tint = colors.textSecondary,
                    )
                }
                Text(
                    "选择此目录",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(path) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (creating) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.cardSecondary)
                            .padding(horizontal = 12.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (newName.isEmpty()) {
                                    Text("新文件夹名称", fontSize = 14.sp, color = colors.textTertiary)
                                }
                                inner()
                            }
                        },
                    )
                    Text(
                        "创建",
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                val name = newName.trim()
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        when (val r = repo.createFolder(path, name)) {
                                            is FileRepository.Result.Ok -> {
                                                creating = false
                                                newName = ""
                                                reload++
                                            }
                                            is FileRepository.Result.Err -> message = r.message
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    color = colors.danger,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(colors.separator),
            )
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                        }
                    }
                } else if (dirs.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text("该目录下没有子文件夹", fontSize = 14.sp, color = colors.textSecondary)
                        }
                    }
                }
                items(dirs, key = { it.fsId }) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { path = d.path }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = iconResFor(d)),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            d.name,
                            fontSize = 15.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
