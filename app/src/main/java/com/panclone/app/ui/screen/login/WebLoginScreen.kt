package com.panclone.app.ui.screen.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text

private const val LOGIN_URL = "https://pan.baidu.com/disk/main"

/**
 * 内嵌官方登录页。风控、验证码、扫码、短信都由百度页面自己处理，
 * 我们只在页面加载完成后从 CookieManager 抓取 BDUSS/STOKEN。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    onCookieCaptured: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("正在打开官方登录页…") }

    BackHandler { onCancel() }

    fun tryCapture(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://pan.baidu.com")
            ?: return false
        if (!cookies.contains("BDUSS=")) return false
        onCookieCaptured(cookies)
        return true
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 每次进入登录页先彻底清掉百度的 web 登录态：
                    // 否则 WebView 带着残留 BDUSS 打开 disk/main 会自动登录，形成"退出又跳进去"的死循环。
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.removeSessionCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        cm.flush()
                    }
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                // 本页创建时已清空所有百度 cookie，此处抓到的 BDUSS 必是用户本次登录产生。
                                // 未登录时抓不到 BDUSS，不会自动进 app，从根上避免"退出又跳进去"。
                                if (url?.contains("/disk/main") == true) {
                                    if (tryCapture()) return
                                    hint = "还没检测到登录态，请继续在页面内完成登录"
                                }
                                CookieManager.getInstance().flush()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false
                        }
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
                update = { webView = it },
            )
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { if (!tryCapture()) hint = "尚未登录完成。请在上方页面完成登录后再点这里。" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("我已登录，提取登录态") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}
