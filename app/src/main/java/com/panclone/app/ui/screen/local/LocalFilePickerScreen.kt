package com.panclone.app.ui.screen.local

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.panclone.app.data.model.PanFile
import com.panclone.app.ui.screen.files.iconResFor
import com.panclone.app.ui.screen.files.isImageFile
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class LocalPickerMode { FILES, FOLDER }

/** 不调用系统 DocumentsUI 的内置本地文件管理器。 */
@Composable
fun LocalFilePickerScreen(
    mode: LocalPickerMode,
    onCancel: () -> Unit,
    onFilesSelected: (List<File>) -> Unit,
    onFolderSelected: (File) -> Unit,
) {
    val colors = LocalAppColors.current
    val root = remember { File("/storage/emulated/0") }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(current.absolutePath) {
        entries = withContext(Dispatchers.IO) {
            current.listFiles().orEmpty()
                .filter { !it.isHidden && it.canRead() }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
        selected = emptySet()
    }

    fun goBack() {
        if (current.absolutePath == root.absolutePath) onCancel()
        else current = current.parentFile?.takeIf { it.absolutePath.startsWith(root.absolutePath) } ?: root
    }
    BackHandler { goBack() }

    Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(58.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::goBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = colors.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (mode == LocalPickerMode.FILES) "选择上传文件" else "选择上传文件夹",
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        current.absolutePath.removePrefix(root.absolutePath).ifBlank { "内部存储" },
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "取消",
                    color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onCancel).padding(10.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries, key = { it.absolutePath }) { file ->
                    val checked = file.absolutePath in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (checked) colors.accent.copy(alpha = 0.13f) else Color.Transparent)
                            .clickable {
                                when {
                                    file.isDirectory -> current = file
                                    mode == LocalPickerMode.FILES -> {
                                        selected = if (checked) selected - file.absolutePath else selected + file.absolutePath
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val model = PanFile(name = file.name, size = file.length(), isDir = if (file.isDirectory) 1 else 0)
                        if (file.isFile && isImageFile(model)) {
                            AsyncImage(
                                model = file,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        } else {
                            Icon(
                                painter = painterResource(iconResFor(model)),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(38.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (file.isDirectory) "文件夹" else formatSize(file.length()),
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        if (checked) {
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(colors.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(110.dp)) }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.barBackground.copy(alpha = 0.78f),
                            colors.barBackground.copy(alpha = 0.62f),
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.accent)
                    .clickable {
                        if (mode == LocalPickerMode.FOLDER) onFolderSelected(current)
                        else onFilesSelected(selected.map(::File).filter(File::isFile))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (mode == LocalPickerMode.FOLDER) "选择当前文件夹"
                        else "上传已选 ${selected.size} 个文件",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
