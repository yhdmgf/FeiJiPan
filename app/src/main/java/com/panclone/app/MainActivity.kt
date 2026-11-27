package com.panclone.app

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.panclone.app.ui.AppRoot
import com.panclone.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val _pipMode = MutableStateFlow(false)
    val pipMode: StateFlow<Boolean> = _pipMode.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        val session = app.session
        submitExternalLink(intent)

        setContent {
            var themeMode by remember { mutableStateOf(session.themeMode) }
            // 以实际生效主题为准；SYSTEM 模式不能一律当作浅色，否则深色桌面上图标会看不见。
            androidx.compose.runtime.LaunchedEffect(themeMode) {
                val dark = when (themeMode) {
                    com.panclone.app.data.model.ThemeMode.DARK -> true
                    com.panclone.app.data.model.ThemeMode.LIGHT -> false
                    com.panclone.app.data.model.ThemeMode.SYSTEM ->
                        (resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            AppTheme(themeMode = themeMode) {
                AppRoot(
                    themeMode = themeMode,
                    onThemeModeChange = { session.themeMode = it; themeMode = it },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submitExternalLink(intent)
    }

    /** 只处理第三方分享的文件；分享链接识别已移除。 */
    private fun submitExternalLink(intent: android.content.Intent?) {
        val app = application as? App
        if (intent?.action == android.content.Intent.ACTION_SEND ||
            intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE
        ) {
            val uris = buildList {
                intent.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                }
                @Suppress("DEPRECATION")
                if (intent.action == android.content.Intent.ACTION_SEND) {
                    intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let(::add)
                } else {
                    intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                        ?.let(::addAll)
                }
            }
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            app?.externalUploads?.submit(uris)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pipMode.value = isInPictureInPictureMode
    }

}
