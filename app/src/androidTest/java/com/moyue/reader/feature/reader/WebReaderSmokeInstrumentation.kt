package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.feature.importbook.*
import com.moyue.reader.parser.web.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Real Room/import/reader UI, with response-byte fixtures at the transport boundary. */
class WebReaderSmokeInstrumentation:Instrumentation() {
    private val checks=JSONObject()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun nodes():List<AccessibilityNodeInfo> {
        val list=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo){list+=n;for(i in 0 until n.childCount)n.getChild(i)?.let {walk(it)}}
        uiAutomation.rootInActiveWindow?.let {walk(it)};return list
    }
    private fun find(s:String)=nodes().lastOrNull {it.text?.toString()==s || it.contentDescription?.toString()==s}
    private fun waitFor(s:String):AccessibilityNodeInfo {repeat(100){find(s)?.let {return it};SystemClock.sleep(100)};error("Missing $s; "+nodes().mapNotNull {it.text ?: it.contentDescription})}
    private fun click(s:String){var n=waitFor(s);while(!n.isClickable && n.parent!=null)n=n.parent;check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(650)}
    private fun tap(x:Float,y:Float){val down=SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
        val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(e,true));e.recycle();SystemClock.sleep(60)};SystemClock.sleep(750)}
    private fun swipe() {
        val down=SystemClock.uptimeMillis()
        for(i in 0..13){val action=when(i){0->MotionEvent.ACTION_DOWN;13->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,900f-i*55f,1000f,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(e,true));e.recycle();SystemClock.sleep(25)}
        SystemClock.sleep(800)
    }
    private fun page():Pair<Int,Int> {
        repeat(100){nodes().mapNotNull {it.text?.toString()}.firstOrNull {Regex("[0-9]+ / [0-9]+").matches(it)}?.let {val p=it.split(" / ");return p[0].toInt() to p[1].toInt()};SystemClock.sleep(100)};error("No page counter")
    }
    private fun verify(value:Boolean,label:String){check(value){label};checks.put(label,true)}
    override fun onStart() {
        val out=Bundle();val owned=mutableListOf<Long>();var restore:com.moyue.reader.core.settings.ReaderPreferences?=null
        lateinit var c:com.moyue.reader.MoyueContainer
        var initialized=false
        try {
            runOnMainSync {c=(targetContext.applicationContext as MoyueApplication).container}
            initialized=true
            val original=runBlocking {c.database.bookDao().observeAll().first()}
            val originalHashes=original.associate {it.id to com.moyue.reader.feature.image.VisualRepository.hash(java.io.File(it.sourcePath))}
            val requestPaths=mutableListOf<String>()
            fun chapter(n:Int)="<meta charset=gbk><title>第${n}章 中文测试</title><h1>第${n}章 中文测试</h1><article>"+"<p>这是不会乱码的完整中文小说正文，包含缩放之外的左右点击翻页验收内容。</p>".repeat(160)+"</article><a href='index_1.html'>查看全部章节 &gt;&gt;</a>"
            fun html(path:String):String {
                if("index_" in path) {
                    val i=path.substringAfter("index_").substringBefore('.').toInt()
                    return "<meta charset=gbk><h1>测试小说目录</h1><div id='list'>"+((i-1)*3+1..i*3).joinToString(""){"<dd><a href='$it.html'>第${it}章 中文测试</a></dd>"}+"</div>"+if(i<35)"<a href='index_${i+1}.html'>下一页 &raquo;</a>" else ""
                }
                return chapter(path.substringAfterLast('/').substringBefore('.').toInt())
            }
            val fetcher=WebContentFetcher(openConnection={url->object:HttpURLConnection(URL(url)) {
                private val raw=html(URL(url).path).toByteArray(Charset.forName("GBK"))
                override fun connect()=Unit;override fun disconnect()=Unit;override fun usingProxy()=false
                override fun getResponseCode()=200;override fun getContentType()="text/html"
                override fun getInputStream():java.io.InputStream {requestPaths+=URL(url).path;return ByteArrayInputStream(raw)}
            }})
            val imports=ImportService(c.applicationContext,c.importCoordinator,fetcher)
            val source=RoomReaderDataSource(c.database,c.storage,webFetcher=fetcher)
            val url="https://fixture.test/v151/21.html"
            val bookId=runBlocking {
                restore=c.preferences.preferences.first()
                val preview=imports.previewWeb(url)
                verify(preview.readable.title=="第21章 中文测试" && '\uFFFD' !in preview.readable.text,"gbkPreviewAndBody")
                val id=(imports.importWeb(preview) as ImportState.Completed).bookId;owned+=id
                val initial=c.database.chapterDao().forBook(id).single()
                val session=ReaderRepository(source).open(id)
                verify(session.chapters.size==105 && session.chapter.index==20,"all105ChaptersAndImportedChapter")
                verify(session.chapters[20].id==initial.id,"chapterIdPreserved")
                verify(requestPaths.count {"index_" in it}==35,"all35DirectoryPagesLoaded")
                verify(requestPaths.filter {"index_" !in it}.all {it.endsWith("21.html")},"chapterBodiesRemainLazy")
                source.load(session.book,session.chapters[104])
                // A directory URL must become a chapter list, not a book containing its link text.
                val directoryPreview=imports.previewWeb("https://fixture.test/v151/index_1.html")
                val directoryId=(imports.importWeb(directoryPreview) as ImportState.Completed).bookId;owned+=directoryId
                val directorySession=ReaderRepository(source).open(directoryId)
                verify(directorySession.chapters.size==105 && directorySession.chapter.index==0,"directoryUrlImportsAllChapters")
                verify((directorySession.chapter.blocks.first() as com.moyue.reader.core.model.ContentBlock.Text).text.contains("不会乱码"),"directoryStartsAtActualChapterBody")
                // Simulate the old UTF-8 decoding loss plus its stale completed catalog marker.
                val broken=chapter(21).toByteArray(Charset.forName("GBK")).toString(Charsets.UTF_8)
                val legacyPreview=WebImportPreview(FetchedWebPage(url,broken),ReadabilityExtractor().extract(broken,url))
                val legacyId=(imports.importWeb(legacyPreview) as ImportState.Completed).bookId;owned+=legacyId
                val legacyRows=c.database.chapterDao().forBook(legacyId);val saved=legacyRows.single()
                val legacyBook=requireNotNull(c.database.bookDao().get(legacyId));val hash=com.moyue.reader.feature.image.VisualRepository.hash(java.io.File(legacyBook.sourcePath))
                c.database.readingProgressDao().upsert(com.moyue.reader.core.database.ReadingProgressEntity(legacyId,saved.id,0,8,.2f,.2f,123))
                c.storage.webCache(legacyId).resolve("catalog-v6.done").writeText("complete")
                val repaired=ReaderRepository(source).open(legacyId)
                verify(repaired.chapters.size==105 && repaired.chapter.id==saved.id,"legacyCatalogAndStableProgressChapter")
                verify(repaired.book.title=="第21章 中文测试" && repaired.chapter.blocks.none {it is com.moyue.reader.core.model.ContentBlock.Text && '\uFFFD' in it.text},"legacyGarbledTitleAndBodyRepaired")
                verify(com.moyue.reader.feature.image.VisualRepository.hash(java.io.File(legacyBook.sourcePath))==hash,"legacyOriginalHtmlRetained")
                verify(c.database.readingProgressDao().get(legacyId)?.charOffset==8,"legacyProgressRetained")
                c.preferences.update {it.copy(pageMode=PageMode.PAGED,autoNextChapter=false)}
                id
            }
            val title=runBlocking {requireNotNull(c.database.bookDao().get(bookId)).title}
            startActivitySync(Intent(targetContext,com.moyue.reader.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("搜索书籍")
            repeat(50){if(nodes().any {it.isEditable})return@repeat;SystemClock.sleep(100)}
            val editable=nodes().first {it.isEditable}
            check(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)}))
            SystemClock.sleep(700);click(title)
            val first=page();verify(first.first==1 && first.second>2,"realReaderPagination")
            tap(960f,1000f);verify(page().first==2,"rightTapNextPage")
            tap(120f,1000f);verify(page().first==1,"leftTapPreviousPage")
            swipe();verify(page().first==2,"swipePaginationRetained")
            tap(120f,1000f);verify(page().first==1,"tapAfterSwipe")
            tap(540f,1000f);waitFor("目录")
            tap(540f,1000f);verify(page().first==1,"centerTapShowsControlsWithoutTurning")
            tap(540f,1000f);waitFor("目录")
            click("目录");waitFor("目录 · 共 105 章");verify(true,"fullCatalogVisible")
            click("下50章");click("下50章")
            waitFor("输入章序号")
            val chapterInput=nodes().first {it.isEditable}
            check(chapterInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"105")}))
            SystemClock.sleep(700)
            click("定位")
            click("第105章 中文测试")
            waitFor("第105章 中文测试")
            tap(540f,1000f);verify(page().first==1,"catalogJumpToLastChapter")
            verify(runBlocking {val current=c.database.readingProgressDao().get(bookId);current?.chapterId==c.database.chapterDao().forBook(bookId).last().id},"lastChapterActuallyOpened")
            tap(540f,1000f);waitFor("返回书架");click("返回书架");waitFor("搜索书籍")
            runBlocking {
                for(book in original){verify(c.database.bookDao().get(book.id)==book,"oldRowUnchanged${book.id}");verify(com.moyue.reader.feature.image.VisualRepository.hash(java.io.File(book.sourcePath))==originalHashes[book.id],"oldSourceUnchanged${book.id}")}
            }
            out.putString("stream","PASS web reader checks=${checks.length()}")
        }catch(e:Throwable){out.putString("stream","FAIL ${e.stackTraceToString()}")}
        finally {
            runBlocking {if(initialized){restore?.let {old->c.preferences.update {old}};for(id in owned){c.database.bookDao().get(id)?.let {c.database.bookDao().delete(it)};c.storage.bookDirectory(id).deleteRecursively();c.storage.webCache(id).deleteRecursively()}}}
            out.putString("checks",checks.toString());finish(if(out.getString("stream").orEmpty().startsWith("PASS"))-1 else 0,out)
        }
    }
}
