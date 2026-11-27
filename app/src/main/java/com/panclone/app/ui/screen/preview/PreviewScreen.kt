package com.panclone.app.ui.screen.preview

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.panclone.app.data.model.PanFile
import com.panclone.app.ui.components.TopFadeBlur
import com.panclone.app.ui.ios.Ios26AlertDialog
import com.panclone.app.ui.screen.files.DirPickerDialog
import com.panclone.app.ui.screen.files.FileDetailDialog
import com.panclone.app.ui.screen.files.FilesViewModel
import com.panclone.app.ui.screen.files.ShareDialog
import com.panclone.app.ui.screen.files.downloadAll
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.ui.theme.LocalAppColors

@Composable
fun PreviewScreen(
    files: List<PanFile>,
    initialIndex: Int,
    onBack: () -> Unit,
    recycleMode: Boolean = false,
    onRecycleRestore: (PanFile) -> Unit = {},
    onRecycleDelete: (PanFile) -> Unit = {},
    vm: PreviewViewModel = viewModel(),
    filesVm: FilesViewModel = viewModel(),
) {
    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("没有可预览的图片", color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    val previewBackdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(files.indices),
        pageCount = { files.size },
    )
    val state by vm.state.collectAsState()
    val filesState by filesVm.state.collectAsState()
    val currentIndex = pagerState.currentPage.coerceIn(files.indices)
    val currentFile = files[currentIndex]
    var controlsVisible by remember { mutableStateOf(recycleMode) }
    var moreExpanded by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<PanFile?>(null) }
    var deleteTarget by remember { mutableStateOf<PanFile?>(null) }

    LaunchedEffect(currentIndex) { moreExpanded = false; renameFile = null }
    LaunchedEffect(filesState.message) {
        if (filesState.message.isNotBlank()) {
            Toast.makeText(context, filesState.message, Toast.LENGTH_SHORT).show()
            filesVm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().layerBackdrop(previewBackdrop)) {
            HorizontalPager(
                state = pagerState,
                key = { files[it].fsId },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val file = files[page]
                val thumb = thumbUrlOf(file)
                val imageUrl = if (recycleMode) null else state.imageUrls[file.fsId]
                LaunchedEffect(file.fsId) { if (!recycleMode) vm.load(file) }
                ZoomableImage(
                    fileId = file.fsId,
                    model = imageUrl ?: thumb,
                    thumbnail = thumb,
                    error = state.errors[file.fsId],
                    onTap = { controlsVisible = !controlsVisible },
                )
            }
        }

        if (controlsVisible) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
                Text(
                    currentFile.name,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${currentIndex + 1} / ${files.size}", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }

        if (controlsVisible) {
            if (recycleMode) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    TopFadeBlur(backdrop = previewBackdrop, modifier = Modifier.matchParentSize())
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ImageBarAction("还原", com.panclone.app.R.drawable.act_open, { onRecycleRestore(currentFile) })
                        ImageBarAction("删除", com.panclone.app.R.drawable.act_delete, { onRecycleDelete(currentFile) })
                    }
                }
            } else {
                ImageBottomBar(
                    file = currentFile,
                    backdrop = previewBackdrop,
                    moreExpanded = moreExpanded,
                    renameFile = renameFile,
                    onDownload = { downloadAll(context, listOf(currentFile)) },
                    onShare = { filesVm.openShare(listOf(currentFile)) },
                    onDelete = { deleteTarget = currentFile },
                    onMore = { moreExpanded = !moreExpanded },
                    onCopy = { filesVm.openDirPicker(true, listOf(currentFile)) },
                    onMove = { filesVm.openDirPicker(false, listOf(currentFile)) },
                    onRename = { renameFile = currentFile },
                    onRenameCancel = { renameFile = null },
                    onRenameConfirm = { name ->
                        if (name.isNotBlank()) {
                            filesVm.rename(currentFile, name.trim())
                            renameFile = null
                            onBack()
                        }
                    },
                    onDetail = { filesVm.openDetail(currentFile) },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (!recycleMode) {
        deleteTarget?.let { file ->
            Ios26AlertDialog(
                title = "删除「${file.name}」？",
                message = "此操作不可撤销。",
                confirmText = "删除",
                destructive = true,
                onConfirm = { filesVm.delete(listOf(file)); deleteTarget = null; onBack() },
                onDismiss = { deleteTarget = null },
            )
        }
        val shareState by filesVm.share.collectAsState()
        if (shareState.files.isNotEmpty()) {
            ShareDialog(
                state = shareState,
                onDismiss = filesVm::closeShare,
                onCreate = filesVm::createShare,
                onModify = filesVm::modifyShare,
            )
        }
        val detailFile by filesVm.detail.collectAsState()
        detailFile?.let { FileDetailDialog(it, thumbUrlOf(it), filesVm::closeDetail) }
        val dirPick by filesVm.dirPick.collectAsState()
        if (dirPick.visible) {
            DirPickerDialog(
                title = if (dirPick.copy) "复制到" else "移动到",
                count = dirPick.files.size,
                onDismiss = filesVm::closeDirPicker,
                onPick = filesVm::confirmDirPicker,
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    fileId: Long,
    model: String?,
    thumbnail: String?,
    error: String?,
    onTap: () -> Unit,
) {
    var scale by remember(fileId) { mutableFloatStateOf(1f) }
    var offset by remember(fileId) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale > 1f) offset + panChange else Offset.Zero
    }
    Box(
        Modifier.fillMaxSize()
            .transformable(state = transformState, canPan = { scale > 1f }, lockRotationOnZoomPan = true)
            .pointerInput(fileId) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; if (scale == 1f) offset = Offset.Zero },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            model != null -> SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                loading = {
                    if (thumbnail != null) AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else CircularProgressIndicator(color = Color.White)
                },
                error = {
                    if (thumbnail != null && thumbnail != model) AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else Text(error ?: "图片加载失败", color = Color.White)
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            error != null -> Text(error, color = Color.White)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun ImageBottomBar(
    file: PanFile,
    backdrop: Backdrop,
    moreExpanded: Boolean,
    renameFile: PanFile?,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onRenameCancel: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    onDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(24.dp)
    var renameText by remember(renameFile?.fsId) { mutableStateOf(renameFile?.name.orEmpty()) }
    val focusRequester = remember(renameFile?.fsId) { FocusRequester() }
    LaunchedEffect(renameFile?.fsId) { if (renameFile != null) runCatching { focusRequester.requestFocus() } }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Box(modifier.clip(shape).padding(bottom = imeBottom)) {
        TopFadeBlur(backdrop = backdrop, modifier = Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (renameFile != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).focusRequester(focusRequester).padding(horizontal = 12.dp).wrapContentHeight(Alignment.CenterVertically),
                    )
                    Text("取消", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onRenameCancel).padding(10.dp))
                    Text("确定", color = Color(0xFF70B7FF), fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onRenameConfirm(renameText) }.padding(10.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ImageBarAction("下载", com.panclone.app.R.drawable.act_download, onDownload)
                    ImageBarAction("分享", com.panclone.app.R.drawable.act_share, onShare)
                    ImageBarAction("删除", com.panclone.app.R.drawable.act_delete, onDelete)
                    ImageBarAction("更多", com.panclone.app.R.drawable.act_more, onMore)
                }
                if (moreExpanded) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ImageBarAction("复制", com.panclone.app.R.drawable.act_copy, onCopy)
                        ImageBarAction("移动", com.panclone.app.R.drawable.act_move, onMove)
                        ImageBarAction("重命名", com.panclone.app.R.drawable.act_rename, onRename)
                        ImageBarAction("文件详细", com.panclone.app.R.drawable.act_detail, onDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ImageBarAction(label: String, @androidx.annotation.DrawableRes iconRes: Int, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = label, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}
