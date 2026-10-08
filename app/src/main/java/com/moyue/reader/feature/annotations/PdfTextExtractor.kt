package com.moyue.reader.feature.annotations

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moyue.reader.feature.pdf.PdfResourceHost
import com.moyue.reader.feature.pdf.PDF_ORIGIN
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

object PdfTextExtractor {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(context: Context, source: File, startPage: Int = 0, onPage: suspend (Int, String) -> Unit) = coroutineScope {
        val extractionScope = this
        withTimeout(300_000) {
            val completed = CompletableDeferred<Unit>()
            var web: WebView? = null
            try {
                withContext(Dispatchers.Main) {
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "请更新 Android System WebView" }
                    val host = PdfResourceHost(context, source)
                    web = WebView(context).apply {
                        settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean { if (com.moyue.reader.BuildConfig.DEBUG) android.util.Log.d("MoyuePdfIndex", message.message()); return true }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) = host.response(request)
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { completed.completeExceptionally(IllegalStateException("PDF 索引内存不足")); return true }
                        }
                        WebViewCompat.addWebMessageListener(this, "MoyueIndex", setOf(PDF_ORIGIN)) { _, message, origin, main, _ ->
                            if (main && origin.toString() == PDF_ORIGIN) {
                                val data = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
                                if (com.moyue.reader.BuildConfig.DEBUG) android.util.Log.d("MoyuePdfIndex", "${data?.optString("type")} page=${data?.optInt("page", -1)}")
                                when (data?.optString("type")) {
                                    "page" -> extractionScope.launch(Dispatchers.IO) {
                                        try { onPage(data.getInt("page"), data.getString("text")); withContext(Dispatchers.Main) { if (com.moyue.reader.BuildConfig.DEBUG) android.util.Log.d("MoyuePdfIndex", "ack page=${data.getInt("page")}"); web?.evaluateJavascript("window.nextIndexPage()", null) } }
                                        catch (e: Exception) { completed.completeExceptionally(e) }
                                    }
                                    "done" -> completed.complete(Unit)
                                    "error" -> completed.completeExceptionally(IllegalStateException(data.optString("message")))
                                }
                            }
                        }
                        loadUrl("$PDF_ORIGIN/assets/pdf/indexer.html#${startPage.coerceAtLeast(0)}")
                    }
                }
                completed.await()
            } finally { withContext(NonCancellable + Dispatchers.Main) { web?.let { it.stopLoading(); WebViewCompat.removeWebMessageListener(it, "MoyueIndex"); it.destroy() } } }
        }
    }
}
