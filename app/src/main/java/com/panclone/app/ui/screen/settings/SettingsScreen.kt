package com.panclone.app.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.panclone.app.App
import com.panclone.app.R
import com.panclone.app.data.model.ThemeMode
import com.panclone.app.ui.ios.Ios26ActionSheet
import com.panclone.app.ui.ios.Ios26Action
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import com.panclone.app.util.formatSize

@Composable
fun SettingsScreen(
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenRecycleBin: () -> Unit = {},
    onOpenMyShare: () -> Unit = {},
    onLogout: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val colors = LocalAppColors.current
    val quota by vm.quota.collectAsState()
    val user by vm.user.collectAsState()
    val vip by vm.vip.collectAsState()
    val session = App.instance.session

    val nowSec = remember { System.currentTimeMillis() / 1000 }
    val curVip = vip?.current
    val vipActive = vip?.vipActive == true
    val prevVip = vip?.previous
    val vipDateText = when {
        vipActive && curVip != null -> {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            "会员有效期至 " + df.format(java.util.Date(curVip.expiredTime * 1000))
        }
        prevVip != null && prevVip.expiredTime > 0 ->
            "会员已过期 ${((nowSec - prevVip.expiredTime) / 86400L).coerceAtLeast(0L)} 天"
        else -> "未开通会员"
    }

    var themeMode by remember { mutableStateOf(session.themeMode) }
    var showThemeSheet by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 120.dp),
    ) {
        // 「我的」标题已按需求移除，保留原布局间距
        Spacer(Modifier.height(58.dp))

        // 账号卡片
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Radius.card))
                .background(colors.cardBackground)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (user.avatarUrl.isNotBlank() && user.avatarUrl != "https:") {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("网", fontSize = 24.sp, color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        user.baiduName.ifBlank { user.netdiskName.ifBlank { session.userName.ifBlank { "已登录" } } },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Image(
                        painter = painterResource(if (vipActive) R.drawable.ic_svip0 else R.drawable.ic_svip1),
                        contentDescription = if (vipActive) "会员" else "会员已过期",
                        modifier = if (vipActive) Modifier.height(15.dp) else Modifier.width(30.dp).height(14.dp),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(vipDateText, fontSize = 12.sp, color = colors.textSecondary)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (quota.total > 0) "已用 ${formatSize(quota.used)} / ${formatSize(quota.total)}"
                    else "空间信息获取中…",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
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

        // 回收站 / 分享记录
        GroupTitle("管理")
        GroupCard {
            SettingRow(title = "回收站", value = "", showChevron = true) { onOpenRecycleBin() }
            SettingRow(title = "分享记录", value = "", showChevron = true) { onOpenMyShare() }
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
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(LocalAppColors.current.separator),
    )
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
