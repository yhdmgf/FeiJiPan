package com.panclone.app.ui.screen.login

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panclone.app.App
import com.panclone.app.data.net.FeiJiPanClient
import com.panclone.app.ui.theme.LocalAppColors
import com.panclone.app.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val WEB_LOGIN_URL = "https://www.feijipan.com/console/files/0"
private const val DETECT_INTERVAL_MS = 3000L

/** 读网页 localStorage 里 vuex 的 JS：返回 JSON 字符串或 null */
private const val READ_VUEX_JS = "localStorage.getItem('vuex')"

/**
 * 网页版 WebView 登录（对齐 wenxi 权威机制）：
 *  - WebView 加载网页版控制台文件页，桌面 UA，开 JS 与 DOM storage。
 *  - 每 3 秒读一次 localStorage 的 vuex，提取 appToken/uuid，校验通过即完成登录进首页。
 *  - 右上角「保存」手动触发一次检测，方便已在网页登录的用户直接进。
 */
@Composable
fun FeiJiWebLoginScreen(
    onLoginSuccess: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    // 手动「保存」触发的额外一次检测（用计数触发 LaunchedEffect）
    var manualDetectTick by remember { mutableIntStateOf(0) }
    // 手动检测是否在飞行中，落地后据结果给 toast
    var manualPending by remember { mutableStateOf(false) }

    val detecting = state is LoginState.Detecting

    // 一次性 toast
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    // 手动检测落地后据结果提示
    LaunchedEffect(state) {
        if (manualPending && state !is LoginState.Detecting) {
            manualPending = false
            if (state is LoginState.Error) {
                Toast.makeText(context, "未检测到有效登录态，请先在网页登录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 登录成功直接进首页
    LaunchedEffect(state) {
        if (state is LoginState.Success) onLoginSuccess()
    }

    // 从 WebView 主线程读一次 vuex
    suspend fun readVuex(): String? {
        val wv = webView ?: return null
        return suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(READ_VUEX_JS) { value -> cont.resume(value) }
        }
    }

    // 每 3 秒自动检测登录态（检测中跳过，避免重复触发）
    LaunchedEffect(webView) {
        if (webView == null) return@LaunchedEffect
        while (isActive) {
            if (!detecting) vm.onVuexRaw(readVuex().orEmpty())
            delay(DETECT_INTERVAL_MS)
        }
    }

    // 手动「保存」触发的额外一次检测
    LaunchedEffect(manualDetectTick) {
        if (manualDetectTick == 0) return@LaunchedEffect
        if (!detecting) vm.onVuexRaw(readVuex().orEmpty())
    }

    // 物理返回也留在登录页：未登录不允许回主框架
    BackHandler { }
    // 已有会话（分享/已登录）才允许从登录页退回主框架；全新安装只能先登录
    val canGoBack = remember { App.instance.session.feiToken.isNotBlank() || App.instance.session.feiShareId.isNotBlank() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .statusBarsPadding(),
    ) {
        // 顶部栏：返回 + 标题 + 保存
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "登录小飞机网盘",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            if (canGoBack) {
                Text(
                    "返回",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { onLoginSuccess() },
                )
            }
            Text(
                "保存",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable {
                        if (!detecting) {
                            manualPending = true
                            manualDetectTick++
                        }
                    },
            )
        }

        // 加载 / 检测进度条
        if (loading || detecting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
            )
        }

        // WebView
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = FeiJiPanClient.DESKTOP_UA
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                        }
                    }
                    loadUrl(WEB_LOGIN_URL)
                }.also { webView = it }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(Radius.card)),
        )
    }
}
