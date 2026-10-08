package com.moyue.reader.feature.importbook

import android.annotation.SuppressLint
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
fun SafeWebViewScreen(initialUrl: String, onBack: () -> Unit, onPureMode: (com.moyue.reader.parser.web.FetchedWebPage) -> Unit) {
    val scope=androidx.compose.runtime.rememberCoroutineScope()
    var capturing by remember {mutableStateOf(false)}
    var ready by remember {mutableStateOf(false)}
    var browserError by remember {mutableStateOf<String?>(null)}
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var webView: WebView? by remember { mutableStateOf(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("网页浏览") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { Button(enabled=ready && !capturing,onClick = {
                    val view=webView ?: return@Button
                    capturing=true;browserError=null
                    scope.launch {
                        try {onPureMode(captureRenderedPage(view))}
                        catch(e:kotlinx.coroutines.CancellationException){throw e}
                        catch(e:Exception){browserError=e.message ?: "网页解析失败"}
                        finally {capturing=false}
                    }
                }) { Text(if(capturing)"正在读取页面…" else "纯净模式") } },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
        browserError?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
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

                        override fun onPageStarted(view:WebView?,url:String?,favicon:android.graphics.Bitmap?) {
                            ready=false;browserError=null
                        }
                        override fun onReceivedHttpError(view:WebView?,request:WebResourceRequest?,response:android.webkit.WebResourceResponse?) {
                            if(request?.isForMainFrame==true){browserError="网页请求失败（HTTP ${response?.statusCode}）";ready=false}
                        }
                        override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:android.webkit.WebResourceError?) {
                            if(request?.isForMainFrame==true){browserError="网页无法访问，请稍后重试";ready=false}
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { currentUrl = it;ready=it.startsWith("http") && browserError==null }
                        }

                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                            handler.cancel();browserError="网页证书校验失败";ready=false
                        }
                    }
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {},
        )
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}
