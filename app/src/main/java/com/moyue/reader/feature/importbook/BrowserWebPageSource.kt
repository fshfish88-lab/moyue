package com.moyue.reader.feature.importbook
import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.*
import com.moyue.reader.parser.web.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One browser session supplies both visible imports and subsequent HTTP requests. */
class BrowserWebPageSource(context:Context):WebPageSource {
 private val appContext=context.applicationContext
 private val renderLock=Mutex()
 private val userAgent=runCatching {WebSettings.getDefaultUserAgent(appContext)}
  .getOrDefault("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
 private val http=WebContentFetcher(requestHeaders={url->
  buildMap {
   put("User-Agent",userAgent)
   CookieManager.getInstance().getCookie(url)?.takeIf {it.isNotBlank()}?.let {put("Cookie",it)}
   put("Referer",URI(url).resolve(".").toString())
  }
 })
 override val supportsRendering=true
 override suspend fun fetch(url:String):FetchedWebPage = try {http.fetch(url)}
  catch(e:CancellationException){throw e}
  catch(e:Exception){render(url)}
 @SuppressLint("SetJavaScriptEnabled")
 override suspend fun render(url:String):FetchedWebPage=renderLock.withLock {
  withContext(Dispatchers.Main) {
   require(URI(url).scheme?.lowercase() in setOf("http","https")) {"仅支持HTTP/HTTPS网页"}
   val view=WebView(appContext)
   try {
    withTimeout(25_000) {
     view.settings.apply {
      javaScriptEnabled=true;domStorageEnabled=true;userAgentString=userAgent
      allowFileAccess=false;allowContentAccess=false
      mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
      javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
     }
     suspendCancellableCoroutine<Unit> {continuation->
      view.webViewClient=object:WebViewClient() {
       override fun shouldOverrideUrlLoading(v:WebView?,request:WebResourceRequest?):Boolean=request?.url?.scheme?.lowercase() !in setOf("http","https")
       override fun onPageFinished(v:WebView?,finalUrl:String?) {if(finalUrl?.startsWith("http")==true && continuation.isActive)continuation.resume(Unit)}
       override fun onReceivedHttpError(v:WebView?,request:WebResourceRequest?,response:WebResourceResponse?) {
        if(request?.isForMainFrame==true && continuation.isActive)continuation.resumeWithException(IOException("网页请求失败（HTTP ${response?.statusCode}）"))
       }
       override fun onReceivedError(v:WebView?,request:WebResourceRequest?,error:WebResourceError?) {
        if(request?.isForMainFrame==true && continuation.isActive)continuation.resumeWithException(IOException("浏览器无法访问网页（${error?.errorCode}）"))
       }
       override fun onReceivedSslError(v:WebView?,handler:SslErrorHandler,error:SslError?) {
        handler.cancel();if(continuation.isActive)continuation.resumeWithException(IOException("网页证书校验失败"))
       }
      }
      view.loadUrl(url)
     }
     delay(1000)
     var previous:FetchedWebPage?=null
     var stable=0
     var captured=captureRenderedPage(view)
     for(attempt in 0..30) {
      delay(300)
      captured=captureRenderedPage(view)
      if(captured.html==previous?.html)stable++ else stable=0
      if(stable>=2 && attempt>=2)break
      previous=captured
     }
     CookieManager.getInstance().flush()
     captured
    }
   } finally {view.stopLoading();view.webViewClient=WebViewClient();view.removeAllViews();view.destroy()}
  }
 }
}
/** Capture the actual displayed document without requesting the URL again. */
suspend fun captureRenderedPage(view:WebView):FetchedWebPage=withContext(Dispatchers.Main) {
 suspendCancellableCoroutine {continuation->
  view.evaluateJavascript("(function(){return JSON.stringify({url:location.href,html:document.documentElement.outerHTML});})()") {value->
   if(continuation.isActive) {
    try {
     val decoded=JSONTokener(value).nextValue() as? String ?: throw IOException("页面尚未准备好")
     val obj=JSONObject(decoded);val url=obj.getString("url");val html=obj.getString("html")
     require(URI(url).scheme?.lowercase() in setOf("http","https")) {"页面地址无效"}
     require(html.isNotBlank() && html.toByteArray(Charsets.UTF_8).size<=5*1024*1024) {"页面为空或超过5MB限制"}
     continuation.resume(FetchedWebPage(url,html,"browser",statusCode=0))
    } catch(e:Exception){continuation.resumeWithException(e)}
   }
  }
 }
}
