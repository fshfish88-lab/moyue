package com.moyue.reader.feature.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moyue.reader.core.document.DocumentMetadata
import com.moyue.reader.core.document.FormatDetector
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.storage.ImportProcessor
import com.moyue.reader.core.storage.RecoverableImportException
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PdfImportProcessor(private val context: Context, private val displayName: String?) : ImportProcessor {
    override suspend fun detect(source: File) {
        if(source.length() > 200L * 1024 * 1024) throw RecoverableImportException("PDF 文件不能超过 200 MB")
        if(!FormatDetector.isPdf(source)) throw RecoverableImportException("文件内容不是 PDF，请检查文件是否完整")
    }
    override suspend fun parse(source: File, sourceType: SourceType): ParsedBook {
        val result = PdfProbe.validate(context, source)
        val title = result.optString("title").trim().takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "PDF 文档"
        val locked = result.optBoolean("locked")
        val cover = if(locked) null else createPdfCover(source)
        return ParsedBook(title, result.optString("author").trim().takeIf(String::isNotBlank), SourceType.DOCUMENT, emptyList(), coverPath = cover?.absolutePath,
            document = DocumentMetadata("PDF", "application/pdf", source.length(), if(locked) null else result.optInt("pages"), locked))
    }
    override suspend fun validate(book: ParsedBook) {
        require(book.document?.let { it.locked || (it.pageCount ?: 0) > 0 } == true) { "PDF 中没有可阅读页面" }
    }
}

/** Metadata validation uses the same offline parser as the reader, including password detection. */
internal object PdfProbe {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun validate(context: Context, source: File): JSONObject = withTimeout(90_000) {
        withContext(Dispatchers.Main) {
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) throw RecoverableImportException("请更新 Android System WebView 后再打开 PDF")
            val web = WebView(context)
            try {
                suspendCancellableCoroutine { continuation ->
                    val host = PdfResourceHost(context, source)
                    web.settings.javaScriptEnabled = true
                    web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean { android.util.Log.d("MoyuePdfProbe",message.message()); return true }
                    }
                    WebViewCompat.addWebMessageListener(web, "MoyueProbe", setOf(PDF_ORIGIN)) { _, message, origin, main, _ ->
                        if(!main || origin.toString() != PDF_ORIGIN || !continuation.isActive) return@addWebMessageListener
                        val value = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                        if(value.optBoolean("ok")) continuation.resume(value)
                        else continuation.resumeWithException(RecoverableImportException(value.optString("error", "PDF 解析失败")))
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) = host.response(request)
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                            if(request.isForMainFrame && continuation.isActive) continuation.resumeWithException(RecoverableImportException("PDF 组件加载失败，请更新 System WebView"))
                        }
                    }
                    web.loadUrl("$PDF_ORIGIN/assets/pdf/probe.html")
                }
            } finally { web.stopLoading(); web.destroy() }
        }
    }
}

internal fun createPdfCover(source: File): File? = runCatching {
    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(0).use { page ->
                val width = 360; val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).coerceIn(120, 720).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    source.parentFile!!.resolve("pdf-cover.png").also { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                } finally { bitmap.recycle() }
            }
        }
    }
}.getOrNull()
