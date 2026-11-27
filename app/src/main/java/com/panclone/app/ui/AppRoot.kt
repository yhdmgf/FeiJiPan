package com.panclone.app.ui

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.panclone.app.App
import com.panclone.app.player.AudioPlaybackManager
import com.panclone.app.data.UploadCoordinator
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.ThemeMode
import com.panclone.app.ui.components.AudioMiniPlayer
import com.panclone.app.ui.components.AudioPlayerHost
import com.panclone.app.ui.components.GlassActionSheet
import com.panclone.app.ui.components.GlassCreateFolderSheet
import com.panclone.app.ui.components.FloatingActionFab
import com.panclone.app.ui.components.IosIcons
import com.panclone.app.ui.components.LiquidGlassTabBar
import com.panclone.app.ui.components.NavItem
import com.panclone.app.ui.screen.files.FilesScreen
import com.panclone.app.ui.screen.files.DirPickerDialog
import com.panclone.app.ui.screen.files.FilesViewModel
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.ui.screen.local.LocalFilePickerScreen
import com.panclone.app.ui.screen.local.LocalPickerMode
import com.panclone.app.ui.screen.login.LoginScreen
import com.panclone.app.ui.screen.login.LoginViewModel
import com.panclone.app.ui.screen.music.MusicScreen
import com.panclone.app.ui.screen.player.AudioPlayerScreen
import com.panclone.app.ui.screen.player.PlayerScreen
import com.panclone.app.ui.screen.preview.PreviewScreen
import com.panclone.app.ui.screen.recycle.RecycleBinScreen
import com.panclone.app.ui.screen.myshare.MyShareScreen
import com.panclone.app.ui.screen.settings.SettingsScreen
import com.panclone.app.ui.screen.transfer.TransferScreen
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.LocalPageBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Screen { LOGIN, MAIN, PLAYER, PREVIEW, AUDIO, LOCAL_PICKER, RECYCLE, MYSHARE }

private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "flv", "ts", "webm", "m4v", "3gp", "rmvb", "wmv")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val AUDIO_EXTS = setOf("mp3", "flac", "aac", "wav", "m4a", "ogg", "ape", "opus", "wma")

// 底栏图标：官方百度网盘 App 资源（nor 默认灰版 / pre 选中蓝版）；
// 「我的」显示登录后的真实头像。
private fun buildNavItems(avatarUrl: String?) = listOf(
    NavItem(
        "文件",
        iconRes = com.panclone.app.R.drawable.tab_file_nor,
        iconResSelected = com.panclone.app.R.drawable.tab_file_pre,
    ),
    NavItem(
        "传输",
        iconRes = com.panclone.app.R.drawable.tab_transfer_nor,
        iconResSelected = com.panclone.app.R.drawable.tab_transfer_pre,
    ),
    NavItem(
        "音乐",
        icon = IosIcons.Music,
    ),
    NavItem(
        "我的",
        iconRes = com.panclone.app.R.drawable.tab_mine_nor,
        avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
    ),
)

@Composable
fun AppRoot(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val session = App.instance.session
    val loginVm: LoginViewModel = viewModel()
    val loggedIn by session.loggedIn.collectAsState()
    var screen by rememberSaveable { mutableStateOf(if (loggedIn) Screen.MAIN else Screen.LOGIN) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<PanFile?>(null) }
    var previewItems by remember { mutableStateOf<List<PanFile>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var localPickerMode by remember { mutableStateOf(LocalPickerMode.FILES) }
    var uploadChoiceVisible by remember { mutableStateOf(false) }
    var uploadRemoteDir by remember { mutableStateOf("/") }
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    val avatarUrl by session.avatarUrlFlow.collectAsState()
    val playback by AudioPlaybackManager.state.collectAsState()
    val playbackArtwork by AudioPlaybackManager.artwork.collectAsState()
    val pendingExternalUpload by App.instance.externalUploads.pending.collectAsState()

    BackHandler(enabled = screen == Screen.PREVIEW || screen == Screen.AUDIO || screen == Screen.RECYCLE || screen == Screen.MYSHARE) {
        screen = Screen.MAIN
    }

    when (screen) {
        Screen.LOGIN -> LoginScreen(onLoggedIn = { screen = Screen.MAIN }, vm = loginVm)
        Screen.RECYCLE -> RecycleBinScreen(onBack = { screen = Screen.MAIN })
        Screen.MYSHARE -> MyShareScreen(onBack = { screen = Screen.MAIN })
        Screen.LOCAL_PICKER -> LocalFilePickerScreen(
            mode = localPickerMode,
            onCancel = { screen = Screen.MAIN },
            onFilesSelected = { files ->
                if (files.isNotEmpty()) {
                    UploadCoordinator.uploadFiles(files, uploadRemoteDir) { count, error ->
                        Toast.makeText(
                            ctx,
                            error ?: "已加入上传队列 $count 项",
                            if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                screen = Screen.MAIN
            },
            onFolderSelected = { folder ->
                UploadCoordinator.uploadFolder(folder, uploadRemoteDir) { count, error ->
                    Toast.makeText(
                        ctx,
                        error ?: "已保留目录结构并加入上传队列 $count 项",
                        if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
                screen = Screen.MAIN
            },
        )
        Screen.MAIN, Screen.AUDIO, Screen.PREVIEW, Screen.PLAYER -> {
            val stateHolder = rememberSaveableStateHolder()
            val pageBackdrop = rememberLayerBackdrop()
            val filesVm: FilesViewModel = viewModel()
            val filesState by filesVm.state.collectAsState()
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val miniVisible = playback.hasMedia && playback.queue.isNotEmpty() && !filesState.selectMode

            var fabShown by remember { mutableStateOf(false) }

            val openUploadPicker = {
                uploadRemoteDir = filesState.path
                if (!android.os.Environment.isExternalStorageManager()) {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                    Toast.makeText(ctx, "请先授予所有文件访问权限", Toast.LENGTH_LONG).show()
                } else {
                    uploadChoiceVisible = true
                }
            }

            CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
                Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
                    stateHolder.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> FilesScreen(
                                vm = filesVm,
                                backdrop = pageBackdrop,
                                onFabVisibility = { show -> fabShown = show },
                                previewing = screen == Screen.PREVIEW || screen == Screen.PLAYER,
                                onUpload = openUploadPicker,
                                onOpenFile = { file, siblings ->
                                    val ext = file.name.substringAfterLast('.', "").lowercase()
                                    when {
                                        ext in VIDEO_EXTS -> {
                                            AudioPlaybackManager.stopForVideo()
                                            current = file
                                            screen = Screen.PLAYER
                                        }
                                        ext in IMAGE_EXTS -> {
                                            previewItems = siblings.filter {
                                                !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
                                            }
                                            previewIndex = previewItems.indexOfFirst { it.fsId == file.fsId }.coerceAtLeast(0)
                                            screen = Screen.PREVIEW
                                        }
                                        ext in AUDIO_EXTS -> {
                                            previewItems = siblings.filter {
                                                !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS
                                            }
                                            previewIndex = previewItems.indexOfFirst { it.fsId == file.fsId }.coerceAtLeast(0)
                                            if (AudioPlaybackManager.state.value.mediaId == file.fsId) {
                                                AudioPlaybackManager.playOrPause()
                                            } else {
                                                AudioPlaybackManager.playQueue(previewItems, previewIndex, fromMusicPage = false)
                                            }
                                        }
                                    }
                                },
                                onLogout = { screen = Screen.LOGIN },
                            )
                            1 -> Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                                TransferScreen()
                            }
                            2 -> Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                                MusicScreen(
                                    onPlay = { queue, index ->
                                        previewItems = queue
                                        previewIndex = index.coerceIn(queue.indices)
                                        AudioPlaybackManager.playQueue(previewItems, previewIndex, fromMusicPage = true)
                                    },
                                )
                            }
                            else -> Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                                SettingsScreen(
                                    onThemeModeChange = onThemeModeChange,
                                    onOpenRecycleBin = { screen = Screen.RECYCLE },
                                    onOpenMyShare = { screen = Screen.MYSHARE },
                                    onLogout = {
                                        // 立刻清 WebView 百度登录态，避免退出后登录页带旧 cookie 又自动登录进来
                                        runCatching {
                                            val cm = android.webkit.CookieManager.getInstance()
                                            cm.removeAllCookies(null)
                                            cm.removeSessionCookies(null)
                                            android.webkit.WebStorage.getInstance().deleteAllData()
                                            cm.flush()
                                        }
                                        AudioPlaybackManager.dismissAndStop()
                                        session.logout()
                                        loginVm.reset()
                                        tab = 0
                                        screen = Screen.LOGIN
                                    },
                                )
                            }
                        }
                    }

                    if (tab == 0) {
                        AnimatedVisibility(
                            visible = !filesState.selectMode && fabShown,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = if (miniVisible) 180.dp else 110.dp),
                        ) {
                            FloatingActionFab(
                                backdrop = pageBackdrop,
                                onUpload = openUploadPicker,
                                onCreateFolder = { filesVm.openCreate() },
                            )
                        }
                    }

                    if (miniVisible) {
                        val currentFile = playback.queue.firstOrNull { it.fsId == playback.mediaId }
                        val art: Any? = playbackArtwork[playback.mediaId] ?: currentFile?.let(::thumbUrlOf)
                        AudioMiniPlayer(
                            title = currentFile?.name ?: playback.title,
                            artwork = art,
                            progress = if (playback.durationMs > 0) {
                                playback.positionMs.toFloat() / playback.durationMs
                            } else 0f,
                            isPlaying = playback.isPlaying,
                            backdrop = pageBackdrop,
                            onOpen = {
                                previewItems = playback.queue
                                previewIndex = playback.index.coerceIn(playback.queue.indices)
                                screen = Screen.AUDIO
                            },
                            onDismissed = {},
                            onExpandProgress = {},
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.92f)
                                .padding(bottom = navBarInset + 82.dp),
                        )
                    }

                    if (!(tab == 0 && filesState.selectMode)) {
                        LiquidGlassTabBar(
                            items = buildNavItems(avatarUrl),
                            selectedIndex = tab,
                            backdrop = pageBackdrop,
                            onSelected = { tab = it },
                        )
                    }

                    if (screen == Screen.AUDIO) {
                        AudioPlayerHost(
                            onDismiss = { screen = Screen.MAIN },
                                                    ) {
                            AudioPlayerScreen(
                                files = previewItems,
                                initialIndex = previewIndex,
                                onBack = { screen = Screen.MAIN },
                            )
                        }
                    }

                    if (uploadChoiceVisible) {
                        GlassActionSheet(
                            backdrop = pageBackdrop,
                            title = "上传内容",
                            actions = listOf(
                                "上传文件" to {
                                    uploadChoiceVisible = false
                                    localPickerMode = LocalPickerMode.FILES
                                    screen = Screen.LOCAL_PICKER
                                },
                                "上传文件夹（保留目录树）" to {
                                    uploadChoiceVisible = false
                                    localPickerMode = LocalPickerMode.FOLDER
                                    screen = Screen.LOCAL_PICKER
                                },
                            ),
                            onDismiss = { uploadChoiceVisible = false },
                        )
                    }

                    if (filesState.showCreate && screen == Screen.MAIN) {
                        GlassCreateFolderSheet(
                            backdrop = pageBackdrop,
                            onDismiss = { filesVm.closeCreate() },
                            onCreate = { name -> filesVm.createFolder(name) },
                        )
                    }

                    // 预览叠加在 MAIN 之上：MAIN 分支保持存活，
                    // 文件列表的滚动状态不会被销毁，返回时原封不动、零滚动。
                    if (screen == Screen.PREVIEW) {
                        PreviewScreen(
                            files = previewItems,
                            initialIndex = previewIndex,
                            onBack = { screen = Screen.MAIN },
                        )
                    }
                    // 视频播放也叠加在 MAIN 之上：MAIN 存活，文件列表不重建，
                    // 从搜索结果点视频返回时原封不动。
                    if (screen == Screen.PLAYER) {
                        PlayerScreen(file = current, onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }

    pendingExternalUpload?.let { pending ->
        if (loggedIn) {
            DirPickerDialog(
                title = "第三方分享上传到",
                count = pending.uris.size,
                onDismiss = { App.instance.externalUploads.consume() },
                onPick = { destination ->
                    val uris = pending.uris
                    App.instance.externalUploads.consume()
                    CoroutineScope(Dispatchers.IO).launch {
                        var count = 0
                        uris.forEach { uri ->
                            val info = queryNameAndSize(ctx, uri) ?: return@forEach
                            val task = App.instance.transfers.addUploadUri(
                                uri = uri,
                                name = info.first,
                                size = info.second,
                                remoteDir = destination,
                            )
                            App.instance.transfers.start(task.id)
                            count++
                        }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已加入上传队列 $count 项", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }
}

/** 读取分享 URI 的显示名与大小（用于原位上传，不复制文件）。 */
private fun queryNameAndSize(ctx: android.content.Context, uri: android.net.Uri): Pair<String, Long>? =
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
            if (name.isNullOrBlank()) null else name to size
        }
    }.getOrNull()
