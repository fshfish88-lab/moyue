package com.moyue.reader.feature.reader
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.feature.importbook.*
import com.moyue.reader.parser.web.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/** Real HTTPS sites; rendered DOM is exercised separately and is not counted as live network evidence. */
class WebLiveInstrumentation:Instrumentation() {
 private var args:Bundle?=null
 override fun onCreate(arguments:Bundle?){args=arguments;super.onCreate(arguments);start()}
 private fun nodes():List<AccessibilityNodeInfo> {
  val list=mutableListOf<AccessibilityNodeInfo>()
  fun walk(n:AccessibilityNodeInfo){list+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::walk)}
  uiAutomation.rootInActiveWindow?.let {it.refresh();walk(it)};return list
 }
 private fun waitFor(text:String):AccessibilityNodeInfo {
  repeat(150){nodes().lastOrNull {it.text?.toString()==text || it.contentDescription?.toString()==text || it.hintText?.toString()==text}?.let {return it};SystemClock.sleep(100)}
  error("Missing $text; "+nodes().mapNotNull {it.text ?: it.contentDescription})
 }
 private fun click(text:String) {var n=waitFor(text);while(!n.isClickable && n.parent!=null)n=n.parent;check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(500)}
 override fun onStart() {
  val out=Bundle();val report=JSONObject();val owned=mutableListOf<Long>()
  lateinit var c:com.moyue.reader.MoyueContainer
  var initialized=false
  try {
   runOnMainSync {c=(targetContext.applicationContext as MoyueApplication).container};initialized=true
   runBlocking {
    val source=RoomReaderDataSource(c.database,c.storage,webFetcher=c.webPages)
    val original=c.database.bookDao().observeAll().first()
    val hashes=original.associate {it.id to com.moyue.reader.feature.image.VisualRepository.hash(File(it.sourcePath))}
    val sites=if(args?.getString("mode")=="render")emptyList() else listOf("https://tianyashuku.net/cn/1780/","https://www.lishirenwu.com/guji/xiyouji/")
    for(url in sites) {
     val preview=c.importService.previewWeb(url)
     val id=(c.importService.importWeb(preview) as ImportState.Completed).bookId;owned+=id
     val book=requireNotNull(c.database.bookDao().get(id))
     val rows=source.chapters(id)
     report.put("debug",JSONObject().put("url",url).put("bookTitle",book.title).put("sourceUrl",book.sourceUrl).put("rowCount",rows.size).put("first",rows.take(2).map {it.title+" | "+it.sourceUrl}).put("last",rows.takeLast(2).map {it.title+" | "+it.sourceUrl}).put("trace",c.storage.webCache(id).resolve("catalog-diagnostic.txt").readText()))
     val expected=if("tianyashuku" in url)101 else 100
     check(rows.size==expected) {"$url expected $expected directory entries; got ${rows.size}"}
     check(book.title.contains("西游记") && !book.title.contains("推荐")) {"Unexpected book title: ${book.title}"}
     check(rows.first().title.contains(Regex("第(?:001|一)回")))
     check(rows[99].title.contains(Regex("第(?:100|一百)回")))
     val details=JSONObject().put("count",rows.size).put("first",rows.first().title).put("last",rows.last().title)
     for(index in listOf(0,49,99)) {
      val chapter=source.load(book,rows[index])
      val text=chapter.blocks.filterIsInstance<ContentBlock.Text>().joinToString {it.text}
      if(index==0)check(text.contains("盖闻天地之数")){"First chapter is not actual novel body"}
      check(text.length>1000 && '\uFFFD' !in text){"$url chapter ${index+1} invalid body"}
      details.put("chapter${index+1}Chars",text.length)
     }
     val trace=c.storage.webCache(id).resolve("catalog-diagnostic.txt").readText()
     check("complete=true" in trace)
     if("lishirenwu" in url)check("list_2.html" in trace && "list_3.html" in trace){trace}
     details.put("trace",trace).put("transport",preview.fetched.transport)
     report.put(url,details)
     val cachedRows=source.chapters(id);check(cachedRows.map {it.id to it.sourceUrl}==rows.map {it.id to it.sourceUrl})
     val current=rows[49]
     c.database.readingProgressDao().upsert(com.moyue.reader.core.database.ReadingProgressEntity(id,current.id,0,8,.2f,.2f,123))
     val progress=c.database.readingProgressDao().get(id)
     val refreshed=source.refreshCatalog(id)
     check(refreshed.size==expected && refreshed[49].id==current.id && c.database.readingProgressDao().get(id)==progress)
     check(c.storage.bookDirectory(id).resolve("web-catalog.url").isFile)
    }
    if(sites.isNotEmpty()) {
     val browserPage=c.webPages.render(sites.first())
     check(browserPage.transport=="browser" && WebCatalogExtractor().chapters(browserPage.html,browserPage.finalUrl).size==101)
     report.put("realHttpsBrowserRender101",true)
    }
    // Execute actual JavaScript in Android WebView and import the resulting DOM without another HTTP request.
    var view:WebView?=null
    val page=try {
     withContext(Dispatchers.Main) {
      WebView(targetContext).also {
       view=it;it.settings.javaScriptEnabled=true
       it.loadDataWithBaseURL("https://dynamic.fixture.test/novel/catalog.html","""<title>动态目录测试</title><h1>动态目录测试</h1><div id='list'><h2>最新章节</h2><a href='1061.html'>第1061章</a></div><table id='all-chapters'></table><script>setTimeout(function(){document.getElementById('all-chapters').innerHTML='<tbody>'+Array.from({length:1080},(_,i)=>'<tr><td><a href="'+(i+1)+'.html">第'+(i+1)+'章 动态正文</a></td></tr>').join('')+'</tbody>';},400);</script>""","text/html","UTF-8",null)
      }
     }
     delay(1500)
     withTimeout(10_000) {
      var captured=captureRenderedPage(requireNotNull(view))
      while(WebCatalogExtractor().chapters(captured.html,captured.finalUrl).size!=1080){delay(300);captured=captureRenderedPage(requireNotNull(view))}
      captured
     }
    } finally {withContext(Dispatchers.Main){view?.destroy()}}
    check(page.transport=="browser")
    val parser=WebCatalogExtractor();check(parser.chapters(page.html,page.finalUrl).size==1080)
    val noRefetch=ImportService(c.applicationContext,c.importCoordinator,object:WebPageSource {override suspend fun fetch(url:String):FetchedWebPage=error("Captured HTML must not be re-fetched")})
    val dynamicId=(noRefetch.importWeb(noRefetch.previewWeb(page)) as ImportState.Completed).bookId;owned+=dynamicId
    val dynamicSource=RoomReaderDataSource(c.database,c.storage,webFetcher=object:WebPageSource {override suspend fun fetch(url:String):FetchedWebPage=error("Catalog already exists in rendered DOM")})
    val dynamicRows=dynamicSource.chapters(dynamicId);check(dynamicRows.size==1080)
    val trace=c.storage.webCache(dynamicId).resolve("catalog-diagnostic.txt").readText()
    check(!trace.contains("Cookie:") && !trace.contains("<html"))
    val partial="<title>动态目录测试</title><h1>动态目录测试</h1><div id='list'><h2>最新章节</h2>"+(1061..1075).joinToString(""){"<a href='$it.html'>第${it}章 最新正文</a>"}+"</div>"
    val partialId=(c.importService.importWeb(c.importService.previewWeb(FetchedWebPage(page.finalUrl,partial))) as ImportState.Completed).bookId;owned+=partialId
    val retrySource=RoomReaderDataSource(c.database,c.storage,webFetcher=object:WebPageSource {
     override val supportsRendering=true
     override suspend fun fetch(url:String)=FetchedWebPage(page.finalUrl,partial)
     override suspend fun render(url:String)=page
    })
    check(retrySource.chapters(partialId).size==1080)
    check("RENDER_REQUEST" in c.storage.webCache(partialId).resolve("catalog-diagnostic.txt").readText())
    report.put("renderedJavaScript1080",true).put("capturedHtmlImportedWithoutRefetch",true).put("automaticRenderedRetryRecovers1080",true)
    if(sites.isNotEmpty()) {
     val id=owned.first();val title="网页实测-100回与附录-$id";val ownedBook=requireNotNull(c.database.bookDao().get(id));c.database.bookDao().update(ownedBook.copy(title=title))
     startActivitySync(Intent(targetContext,com.moyue.reader.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
     click("搜索书籍")
     val input=withTimeout(15000) {
      var input:AccessibilityNodeInfo?=null
      while(input==null) {input=nodes().firstOrNull {it.isEditable || it.actionList.any {action -> action.id==AccessibilityNodeInfo.ACTION_SET_TEXT}};if(input==null)delay(100)}
      input
     }
     check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)}))
     SystemClock.sleep(650);click(title);SystemClock.sleep(1200)
     if(nodes().none {it.text?.toString()=="目录"}) {
      val down=SystemClock.uptimeMillis()
      for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
       val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,540f,950f,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()
      }
     }
     click("目录");waitFor("目录 · 共 101 章");waitFor("目录来源");waitFor("导出诊断")
     val bitmap=requireNotNull(uiAutomation.takeScreenshot());File(targetContext.getExternalFilesDir(null),"v154-live-catalog.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
     click("目录来源");waitFor("完整目录网址");click("取消");click("关闭目录")
     report.put("realCatalogUi101",true).put("sourceAndDiagnosticUi",true)
    }
    for(book in original){check(c.database.bookDao().get(book.id)==book);check(com.moyue.reader.feature.image.VisualRepository.hash(File(book.sourcePath))==hashes[book.id])}
    report.put("originalBookRowsAndSourcesUnchanged",true)
   }
   out.putString("stream","PASS real web and rendered DOM\n"+report.toString())
  } catch(e:Throwable){out.putString("stream","FAIL real web\n"+e.stackTraceToString()+"\n"+report.toString())}
  finally {
   if(initialized)runBlocking {
    for(id in owned){c.database.bookDao().get(id)?.let {c.database.bookDao().delete(it)};c.storage.bookDirectory(id).deleteRecursively();c.storage.webCache(id).parentFile?.deleteRecursively()}
   }
  }
  finish(if(out.getString("stream")!!.startsWith("PASS"))0 else 1,out)
 }
}
