package com.panclone.app.ui.screen.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.panclone.app.ui.theme.LocalAppColors

/** 有效期选项（与网页版一致：0 = 永久） */
private val PERIODS = listOf("1天" to 1, "7天" to 7, "30天" to 30, "365天" to 365, "永久" to 0)

private fun randomPwd(): String {
    val chars = "abcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}

/**
 * 分享弹层（现代化重设计）：
 * 设置（有效期/提取码）→ 结果（二维码/链接/提取码/复制/编辑）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareDialog(
    state: ShareUiState,
    onDismiss: () -> Unit,
    onCreate: (Int, String) -> Unit,
    onModify: (Int, String) -> Unit,
) {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    var customPwd by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }

    fun copy(text: String, tip: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("panclone", text))
        Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
    }

    PanSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            val isEdit = state.done && editing
            if (!state.done || editing) {
                // ---------------- 设置 ----------------
                Text(
                    if (isEdit) "编辑分享" else "分享",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                if (state.files.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.files.joinToString("、") { it.name },
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))

                // 有效期
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardSecondary)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column {
                        Text("有效期", fontSize = 12.sp, color = colors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PERIODS.forEach { (label, value) ->
                                val selected = period == value
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) colors.accent else colors.cardBackground)
                                        .clickable { period = value }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) Color.White else colors.textPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 提取码
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardSecondary)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column {
                        Text("提取码", fontSize = 12.sp, color = colors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("随机生成" to false, "自定义" to true).forEach { (label, value) ->
                                val selected = customPwd == value
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) colors.accent else colors.cardBackground)
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            customPwd = value
                                            if (value && pwd.isBlank()) pwd = randomPwd()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) Color.White else colors.textPrimary,
                                    )
                                }
                            }
                            if (customPwd) {
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = { pwd = it.take(4) },
                                    placeholder = { Text("4位", fontSize = 13.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.width(110.dp),
                                )
                            } else {
                                Text(
                                    "创建时自动生成",
                                    fontSize = 13.sp,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }

                if (state.error.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(state.error, fontSize = 13.sp, color = colors.danger)
                }
                Spacer(Modifier.height(18.dp))

                // 通栏按钮
                val btnText = if (isEdit) "保存修改" else "创建链接分享"
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.accent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (customPwd && pwd.length != 4) {
                                Toast.makeText(ctx, "提取码需为 4 位字母或数字", Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            val finalPwd = if (customPwd) pwd else randomPwd()
                            if (isEdit) onModify(period, finalPwd) else onCreate(period, finalPwd)
                            editing = false
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.creating) "请稍候…" else btnText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                         .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", fontSize = 14.sp, color = colors.textSecondary)
                }
            } else {
                // ---------------- 结果 ----------------
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "分享创建成功",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "任何人通过链接和提取码即可访问",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(14.dp))

                // 二维码卡片
                if (state.qrcodeUrl.isNotBlank()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .border(0.7.dp, colors.separator, RoundedCornerShape(18.dp))
                                .padding(10.dp),
                        ) {
                            AsyncImage(
                                model = state.qrcodeUrl,
                                contentDescription = "二维码",
                                modifier = Modifier.size(140.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 链接行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardSecondary)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("链接", fontSize = 11.sp, color = colors.textSecondary)
                        Text(
                            state.link,
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "复制",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) {
                                val text = if (state.pwd.isBlank()) state.link else "${state.link} 提取码:${state.pwd}"
                                copy(text, "链接和提取码已复制")
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.cardSecondary)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { editing = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("编辑", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.accent)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}
