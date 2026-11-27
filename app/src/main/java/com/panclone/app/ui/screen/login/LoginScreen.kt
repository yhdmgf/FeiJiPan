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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * 登录入口：默认走官方 WebView 登录，Cookie 粘贴作为兜底。
 * 表单本身用 Material3 承载，主界面才使用 miuix / coui 双风格。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    // 默认 WebView 官方登录页（百度没有账号密码协议，必须走 WebView）；Cookie 粘贴作为兜底。
    var showWeb by rememberSaveable { mutableStateOf(true) }
    var cookieText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is LoginState.Success) onLoggedIn()
    }

    when (state) {
        is LoginState.Loading, is LoginState.Success -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        else -> {
            if (showWeb) {
                WebLoginScreen(
                    onCookieCaptured = { vm.loginWithCookie(it) },
                    onCancel = { showWeb = false },
                )
            } else {
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text("登录网盘", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "推荐用官方页面登录；也可以从浏览器登录后复制 Cookie 粘贴到这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        label = { Text("Cookie（含 BDUSS=…）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
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
                        onClick = { vm.loginWithCookie(cookieText) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("用 Cookie 登录") }
                    OutlinedButton(onClick = { showWeb = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("返回官方登录")
                    }
                }
            }
        }
    }
}
