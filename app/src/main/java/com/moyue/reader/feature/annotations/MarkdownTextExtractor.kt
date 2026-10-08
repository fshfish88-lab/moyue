package com.moyue.reader.feature.annotations

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import androidx.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject

object MarkdownTextExtractor {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(context: Context, text: String): String = withTimeout(60000) {
        val completed = CompletableDeferred<String>()
        var web: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "请更新 Android System WebView" }
                val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                web = WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) = loader.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { completed.completeExceptionally(IllegalStateException("Markdown 索引内存不足")); return true }
                    }
                    WebViewCompat.addWebMessageListener(this, "MoyueMdIndex", setOf("https://appassets.androidplatform.net")) { _, message, _, main, _ ->
                        if (main) {
                            val data = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
                            if (data?.optString("type") == "ready") evaluateJavascript("window.indexMarkdown(${JSONObject.quote(text)})", null)
                            if (data?.optString("type") == "text") completed.complete(data.getString("text"))
                        }
                    }
                    loadUrl("https://appassets.androidplatform.net/assets/markdown/indexer.html")
                }
            }
            completed.await()
        } finally { withContext(NonCancellable + Dispatchers.Main) { web?.let { it.stopLoading(); WebViewCompat.removeWebMessageListener(it, "MoyueMdIndex"); it.destroy() } } }
    }
}
