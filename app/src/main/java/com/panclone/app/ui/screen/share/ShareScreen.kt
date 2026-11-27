package com.panclone.app.ui.screen.share

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panclone.app.ui.ios.copyText
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius

private val shareHistory = mutableStateListOf<Pair<String, String>>()

fun recordShare(link: String, pwd: String) {
    if (shareHistory.none { it.first == link }) shareHistory.add(0, link to pwd)
}

@Composable
fun ShareScreen() {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Text(
            "分享",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 14.dp),
        )

        if (shareHistory.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Link,
                    null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有分享", fontSize = 17.sp, color = colors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text("在文件页长按文件即可创建分享", fontSize = 13.sp, color = colors.textTertiary)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp),
            ) {
                shareHistory.forEach { (link, pwd) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(Radius.card))
                            .background(colors.cardBackground)
                            .clickable {
                                ctx.copyText(if (pwd.isBlank()) link else "$link 提取码 $pwd")
                                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Link,
                            null,
                            tint = colors.accent,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(link, fontSize = 15.sp, color = colors.textPrimary, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (pwd.isBlank()) "无提取码 · 点击复制" else "提取码 $pwd · 点击复制",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
