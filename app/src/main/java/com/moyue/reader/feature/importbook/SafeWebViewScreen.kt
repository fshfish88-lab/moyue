package com.moyue.reader.feature.importbook

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeWebViewScreen(initialUrl: String, onBack: () -> Unit, onPureMode: (String) -> Unit) {
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var webView: WebView? by remember { mutableStateOf(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网页浏览") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { Button(onClick = { onPureMode(currentUrl) }) { Text("纯净模式") } },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme.orEmpty()
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { currentUrl = it }
                        }

                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                            handler.cancel()
                        }
                    }
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            update = {},
        )
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}
