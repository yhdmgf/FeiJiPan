package com.panclone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.panclone.app.App
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import kotlinx.coroutines.launch

/**
 * 风控强制验证对话框（errno=132）。
 *
 * 完整链路（网页版抓包移植）：
 * 1) gettemplatevariable 取 token
 * 2) passport /v2/sapi/authwidgetverify?action=getapi 查可用方式
 * 3) action=send 发送短信验证码
 * 4) /v2/getpublickey 取 RSA 公钥
 * 5) action=check 提交 RSA 加密后的验证码
 * 6) /api/report/user?action=sapi_auth 写入会话
 * 7) onSuccess() 回调重试原操作
 */
@Composable
fun VerifyDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val colors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { App.instance.api }

    var step by remember { mutableStateOf("准备验证…") }
    var phone by remember { mutableStateOf("") }
    var authAction by remember { mutableStateOf("recyclebin") }
    var code by remember { mutableStateOf("") }
    val tokenState = remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var canResend by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    suspend fun startVerify() {
        busy = true
        error = ""
        step = "正在获取验证信息…"
        val vars = api.templateVariables()
        if (vars.token.isBlank()) {
            error = "获取验证信息失败（未取到 token，请重新登录后再试）"
            busy = false; return
        }
        tokenState.value = vars.token
        step = "正在查询验证方式…"
        val apiResp = api.verifyGetApi(vars.token)
        if (!apiResp.isSuccess) {
            error = apiResp.msg.ifBlank { "验证方式查询失败（${apiResp.errno}）" }; busy = false; return
        }
        authAction = apiResp.ppAuthName.ifBlank { "recyclebin" }
        phone = apiResp.data?.mobile ?: apiResp.data?.originalMobile ?: ""
        step = "正在发送短信验证码…"
        val sendResp = api.verifySendSms(vars.token, authAction)
        if (!sendResp.isSuccess) {
            error = sendResp.msg.ifBlank { "验证码发送失败（${sendResp.errno}）" }; busy = false; return
        }
        countdown = 60
        canResend = false
        step = "验证码已发送"
        busy = false
    }

    LaunchedEffect(Unit) { startVerify() }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        } else if (step.contains("已发送")) {
            canResend = true
        }
    }

    fun submitCode() {
        if (code.length < 4) { error = "请输入验证码"; return }
        scope.launch {
            busy = true
            error = ""
            step = "正在校验…"
            val pk = api.verifyPublicKey()
            if (pk.pubkey.isBlank() || pk.key.isBlank()) {
                error = "获取加密密钥失败"; busy = false; return@launch
            }
            val encrypted = api.rsaEncrypt(code, pk.pubkey)
            if (encrypted.isBlank()) {
                error = "验证码加密失败"; busy = false; return@launch
            }
            val check = api.verifyCheckSms(tokenState.value, encrypted, pk.key)
            if (!check.isSuccess) {
                error = check.msg.ifBlank { "验证码错误（${check.errno}）" }; busy = false; return@launch
            }
            step = "正在写入验证状态…"
            val commit = api.verifyCommitAuth()
            if (commit.errno != 0) {
                error = commit.showMsg.ifBlank { "验证状态写入失败" }; busy = false; return@launch
            }
            busy = false
            android.widget.Toast.makeText(ctx, "验证成功，正在重试操作", android.widget.Toast.LENGTH_SHORT).show()
            onSuccess()
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.cardBackground, RoundedCornerShape(Radius.card))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("安全验证", fontSize = 19.sp, color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(step, fontSize = 13.sp, color = colors.textSecondary)
            if (phone.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("短信已发送至 $phone", fontSize = 13.sp, color = colors.textSecondary)
            }
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("短信验证码") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.separator,
                ),
            )

            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error, fontSize = 12.sp, color = colors.danger)
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (canResend && !busy) { scope.launch { startVerify() } } }, enabled = canResend && !busy) {
                    Text(if (countdown > 0) "${countdown}s 后重发" else "重新发送", color = if (canResend) colors.accent else colors.textTertiary)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = colors.textSecondary) }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Radius.card))
                        .background(colors.accent)
                        .clickable(enabled = !busy) { submitCode() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("确定", fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
