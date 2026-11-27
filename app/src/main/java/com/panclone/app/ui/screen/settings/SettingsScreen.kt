package com.panclone.app.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panclone.app.App
import com.panclone.app.data.model.ThemeMode
import com.panclone.app.ui.ios.Ios26ActionSheet
import com.panclone.app.ui.ios.Ios26Action
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius

@Composable
fun SettingsScreen(
    onThemeModeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = App.instance.session
    val accountLabel by vm.accountLabel.collectAsState()

    var themeMode by remember { mutableStateOf(session.themeMode) }
    var showThemeSheet by remember { mutableStateOf(false) }

    // 下载设置（小飞机下载功能核心，保留）
    var downloadThreads by remember { mutableIntStateOf(session.downloadThreads) }
    val downloadDir = remember { session.downloadDir }
    var probeResult by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 120.dp),
    ) {
        // 「我的」标题已按需求移除，保留原布局间距
        Spacer(Modifier.height(58.dp))

        // 当前登录身份
        if (accountLabel.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(colors.cardBackground)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(accountLabel, fontSize = 17.sp, color = colors.textPrimary)
            }
        }

        Spacer(Modifier.height(24.dp))

        // 外观
        GroupTitle("外观")
        GroupCard {
            SettingRow(
                title = "外观模式",
                value = when (themeMode) {
                    ThemeMode.SYSTEM -> "跟随系统"
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                },
                showChevron = true,
            ) { showThemeSheet = true }
        }

        Spacer(Modifier.height(20.dp))

        // 下载
        GroupTitle("下载")
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载线程数", fontSize = 17.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text("$downloadThreads", fontSize = 17.sp, color = colors.textSecondary)
                }
                Slider(
                    value = downloadThreads.toFloat(),
                    onValueChange = { downloadThreads = it.toInt() },
                    onValueChangeFinished = { session.downloadThreads = downloadThreads },
                    valueRange = 1f..1280f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingRow(
                title = "下载目录",
                value = downloadDir.ifBlank { "默认" },
                showChevron = false,
            ) { }
            SettingRow(
                title = "下载链路自检",
                value = if (probeResult.isNotBlank()) probeResult else "",
                showChevron = true,
            ) {
                vm.probeDownload { result ->
                    probeResult = result
                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        GroupCard {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { onLogout() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("退出登录", fontSize = 17.sp, color = colors.danger)
            }
        }

    }

    if (showThemeSheet) {
        Ios26ActionSheet(
            title = "外观模式",
            actions = listOf(
                Ios26Action("跟随系统") {
                    themeMode = ThemeMode.SYSTEM; onThemeModeChange(ThemeMode.SYSTEM); showThemeSheet = false
                },
                Ios26Action("浅色") {
                    themeMode = ThemeMode.LIGHT; onThemeModeChange(ThemeMode.LIGHT); showThemeSheet = false
                },
                Ios26Action("深色") {
                    themeMode = ThemeMode.DARK; onThemeModeChange(ThemeMode.DARK); showThemeSheet = false
                },
            ),
            onDismiss = { showThemeSheet = false },
        )
    }

}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 13.sp,
        color = LocalAppColors.current.textSecondary,
        modifier = Modifier.padding(start = 32.dp, bottom = 7.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(LocalAppColors.current.cardBackground),
    ) { content() }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) colors.press else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(value, fontSize = 17.sp, color = colors.textSecondary)
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
