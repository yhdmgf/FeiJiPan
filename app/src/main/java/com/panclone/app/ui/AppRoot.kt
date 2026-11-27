package com.panclone.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.panclone.app.App
import com.panclone.app.player.AudioPlaybackManager
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.model.ThemeMode
import com.panclone.app.ui.components.AudioMiniPlayer
import com.panclone.app.ui.components.AudioPlayerHost
import com.panclone.app.ui.components.IosIcons
import com.panclone.app.ui.components.LiquidGlassTabBar
import com.panclone.app.ui.components.NavItem
import com.panclone.app.ui.screen.files.FilesScreen
import com.panclone.app.ui.screen.files.FilesViewModel
import com.panclone.app.ui.screen.files.thumbUrlOf
import com.panclone.app.ui.screen.login.LoginScreen
import com.panclone.app.ui.screen.login.LoginViewModel
import com.panclone.app.ui.screen.player.AudioPlayerScreen
import com.panclone.app.ui.screen.player.PlayerScreen
import com.panclone.app.ui.screen.preview.PreviewScreen
import com.panclone.app.ui.screen.settings.SettingsScreen
import com.panclone.app.ui.screen.transfer.TransferScreen
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.LocalPageBackdrop

private enum class Screen { LOGIN, MAIN, PLAYER, PREVIEW, AUDIO }

private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "flv", "ts", "webm", "m4v", "3gp", "rmvb", "wmv")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val AUDIO_EXTS = setOf("mp3", "flac", "aac", "wav", "m4a", "ogg", "ape", "opus", "wma")

// 底栏图标：文件 / 传输 / 我的（小飞机网盘版，无音乐分类页）。
private fun buildNavItems() = listOf(
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
        "我的",
        iconRes = com.panclone.app.R.drawable.tab_mine_nor,
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
    val colors = LocalAppColors.current
    val playback by AudioPlaybackManager.state.collectAsState()
    val playbackArtwork by AudioPlaybackManager.artwork.collectAsState()

    BackHandler(enabled = screen == Screen.PREVIEW || screen == Screen.AUDIO) {
        screen = Screen.MAIN
    }

    when (screen) {
        Screen.LOGIN -> LoginScreen(onLoggedIn = { screen = Screen.MAIN }, vm = loginVm)
        Screen.MAIN, Screen.AUDIO, Screen.PREVIEW, Screen.PLAYER -> {
            val stateHolder = rememberSaveableStateHolder()
            val pageBackdrop = rememberLayerBackdrop()
            val filesVm: FilesViewModel = viewModel()
            val filesState by filesVm.state.collectAsState()
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val miniVisible = playback.hasMedia && playback.queue.isNotEmpty() && !filesState.selectMode

            CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
                Box(Modifier.fillMaxSize().background(colors.pageBackground)) {
                    stateHolder.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> FilesScreen(
                                vm = filesVm,
                                backdrop = pageBackdrop,
                                onFabVisibility = {},
                                previewing = screen == Screen.PREVIEW || screen == Screen.PLAYER,
                                onUpload = {},
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
                            else -> Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                                SettingsScreen(
                                    onThemeModeChange = onThemeModeChange,
                                    onLogout = {
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
                            items = buildNavItems(),
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

                    // 预览叠加在 MAIN 之上：MAIN 分支保持存活，
                    // 文件列表的滚动状态不会被销毁，返回时原封不动、零滚动。
                    if (screen == Screen.PREVIEW) {
                        PreviewScreen(
                            files = previewItems,
                            initialIndex = previewIndex,
                            onBack = { screen = Screen.MAIN },
                        )
                    }
                    // 视频播放也叠加在 MAIN 之上：MAIN 存活，文件列表不重建。
                    if (screen == Screen.PLAYER) {
                        PlayerScreen(file = current, onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }
}
