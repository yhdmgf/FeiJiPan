package com.panclone.app.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 登录入口（小飞机网盘）：
 *  - 分享解析（默认）：粘贴分享链接 + 提取码，匿名浏览下载。
 *  - 个人网盘：粘贴 access token + 用户 id。
 * 表单用 Material3 承载，主界面才使用 miuix / coui 双风格。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    // 0 = 分享解析，1 = 个人网盘
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var shareUrl by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var userId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is LoginState.Success) onLoggedIn()
    }

    when (state) {
        is LoginState.Loading, is LoginState.Success -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        else -> {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(40.dp))
                Text("小飞机网盘", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "解析分享链接即可匿名浏览下载；也可以登录个人网盘管理自己的文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == 0,
                        onClick = { mode = 0; vm.reset() },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("分享解析") }
                    SegmentedButton(
                        selected = mode == 1,
                        onClick = { mode = 1; vm.reset() },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("个人网盘") }
                }

                if (mode == 0) {
                    OutlinedTextField(
                        value = shareUrl,
                        onValueChange = { shareUrl = it },
                        label = { Text("分享链接") },
                        placeholder = { Text("https://www.feijipan.com/s/…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("提取码（链接里带了可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    val err = state as? LoginState.Error
                    if (err != null) {
                        Text(
                            err.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { vm.parseShare(shareUrl, code) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("解析并进入") }
                } else {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("access token") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it },
                        label = { Text("用户 id") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    Text(
                        "从网页版登录后可在开发者工具的网络请求里找到 appToken 与 userId。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val err = state as? LoginState.Error
                    if (err != null) {
                        Text(
                            err.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { vm.loginPersonal(token, userId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("登录个人网盘") }
                }
            }
        }
    }
}
