package com.moyue.reader.feature.annotations

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import com.moyue.reader.*
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.*
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.feature.importbook.ImportState
import com.moyue.reader.feature.reader.NativePageLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

class V160Instrumentation : Instrumentation() {
    private var suite = "data"
    private var count = 0
    private fun verify(value: Boolean, label: String) { check(value) { label }; count++; sendStatus(1, Bundle().apply { putString("stream", "check $count: $label") }) }
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite") ?: "data"; super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        try {
            lateinit var c: MoyueContainer
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            if (suite == "snapshot") {
                val db = c.database.openHelper.readableDatabase
                val snapshot = JSONObject().put("schema", db.version)
                for (table in listOf("books", "reading_progress", "document_states", "document_bookmarks", "annotations")) {
                    val rows = org.json.JSONArray()
                    db.query("SELECT * FROM $table ORDER BY 1").use { cursor -> while(cursor.moveToNext()) {
                        val row=JSONObject();for(i in 0 until cursor.columnCount)row.put(cursor.getColumnName(i),if(cursor.isNull(i))JSONObject.NULL else cursor.getString(i));rows.put(row)
                    } }
                    snapshot.put(table, rows)
                }
                val hashes=JSONObject()
                db.query("SELECT id,sourcePath FROM books").use {cursor->while(cursor.moveToNext()){val f=File(cursor.getString(1));if(f.isFile)hashes.put(cursor.getLong(0).toString(),com.moyue.reader.feature.image.VisualRepository.hash(f))}}
                snapshot.put("sourceHashes",hashes)
                out.putString("snapshot",snapshot.toString());out.putString("stream","PASS snapshot");finish(-1,out);return
            }
            if (suite in setOf("ui", "pdfproof")) ui(c, onlyPdf = suite == "pdfproof") else runBlocking { withTimeout(180000) { data(c) } }
            out.putString("stream", "PASS v160 $suite checks=$count"); finish(-1, out)
        } catch (e: Throwable) { out.putString("stream", "FAIL ${e.stackTraceToString()}"); finish(0, out) }
    }
    private suspend fun data(c: MoyueContainer) {
        // Clean only fixtures left by an interrupted run, never real library content.
        c.database.bookDao().observeAll().first().filter { it.title in setOf("V160验证文本", "V160验证文本.txt", "V160UI文本", "V160UI文档", "V160UI PDF") }.forEach {
            c.database.bookDao().delete(it); c.storage.bookDirectory(it.id).deleteRecursively()
        }
        c.database.searchDao().prune()
        // Use the real version-2 schema, rather than re-creating an approximation of its tables.
        val name = "qa-v160-migration-${System.nanoTime()}.db"
        val schema = JSONObject(context.assets.open("migration-fixtures/schema2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = targetContext.openOrCreateDatabase(name, 0, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val item = entities.getJSONObject(i); val table = item.getString("tableName")
                old.execSQL(item.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices=item.optJSONArray("indices")
                if(indices!=null)for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books VALUES(1,'迁移旧书','作者','TXT','/qa/old.txt','UTF-8',NULL,NULL,1,100,123,456,0.4)")
            old.execSQL("INSERT INTO chapters VALUES(9,1,'旧章节',0,0,100,NULL,NULL,NULL,NULL)")
            old.execSQL("INSERT INTO reading_progress VALUES(1,9,0,6,0.4,0.4,456)")
            old.execSQL("INSERT INTO books VALUES(2,'旧PDF',NULL,'DOCUMENT','/qa/old.pdf',NULL,NULL,NULL,0,0,123,456,0.5)")
            old.execSQL("INSERT INTO document_metadata VALUES(2,'PDF','application/pdf',100,8,0)")
            old.execSQL("INSERT INTO document_bookmarks VALUES(7,2,'{\"pageIndex\":4,\"left\":10,\"top\":20,\"scale\":\"1.5\"}','第5页',789)")
            old.version = 2
        } finally { old.close() }
        val migrated = Room.databaseBuilder(targetContext, MoyueDatabase::class.java, name).addMigrations(MoyueDatabase.MIGRATION_1_2, MoyueDatabase.MIGRATION_2_3).build()
        try {
            verify(migrated.openHelper.readableDatabase.version == 3, "real schema2 migrates to validated schema3")
            verify(migrated.bookDao().get(1)?.title == "迁移旧书", "old book retained")
            verify(migrated.readingProgressDao().get(1)?.charOffset == 6, "old reading position retained")
            verify(migrated.documentDao().bookmarks(2).first().single().createdAt == 789L, "legacy bookmark retained")
            val mark = migrated.annotationDao().forBook(2).first().single()
            verify(mark.id == 7L && JSONObject(mark.anchorJson).getJSONObject("position").getInt("pageIndex") == 4 && mark.createdAt == 789L, "legacy bookmark copied once with exact coordinates and timestamp")
            val text = "海上明月照天涯，重复明月。Emoji😀 and MiXeD text."
            val dao = migrated.searchDao()
            val id = dao.addChunk(SearchChunkEntity(bookId = 1, partKey = "qa", location = "旧章节", anchorJson = "{}", text = text, sourceHash = textHash(text)))
            dao.addTerms(SearchFtsEntity(id, SearchTerms.index(text)))
            for (q in listOf("明月照", "月", "Emoji😀", "mixed", "，重复")) verify(dao.search(SearchTerms.query(q), q, 10, 0).singleOrNull()?.chunk?.text == text, "FTS substring $q terms=${SearchTerms.query(q)}")
            verify(dao.search(SearchTerms.query("不存在"), "不存在", 10, 0).isEmpty(), "no false positive")
            dao.deletePartTerms(1, "qa"); dao.deletePart(1, "qa")
            verify(dao.search(SearchTerms.query("明月"), "明月", 10, 0).isEmpty(), "deleted text disappears")
        } finally { migrated.close(); targetContext.deleteDatabase(name) }

        val blocks = listOf(ContentBlock.Text("  第一段明月照天涯😀".repeat(15)), ContentBlock.Text("第二段后续文字".repeat(20)), ContentBlock.Image("/qa/image", "图"), ContentBlock.Text("最后一段星光".repeat(20)))
        for (width in listOf(420, 700)) for (indent in listOf(false, true)) {
            val pages = NativePageLayout.create(blocks, ReaderPreferences(indentParagraphs = indent), width, 600, 32f)
            verify(pages.any { it.image != null }, "image page retained")
            for (p in pages.filter { it.image == null }) for (s in p.segments) {
                val displayed = p.text.substring(s.displayStart, s.displayEnd)
                val original = blockText(blocks[s.blockIndex]).substring(s.sourceStart, s.sourceStart + displayed.length)
                verify(displayed == original, "native page mapping width=$width indent=$indent")
            }
        }

        val originalBooks = c.database.bookDao().observeAll().first()
        val mdPlain=MarkdownTextExtractor.extract(targetContext,"# 标题\n\n明月**照天涯**，星光入梦。\n\n[链接文字](https://example.com)\n")
        verify(mdPlain.contains("明月照天涯") && !mdPlain.contains("**") && !mdPlain.contains("https://"),"Markdown index uses rendered text")
        val originalHash = originalBooks.associate { it.id to File(it.sourcePath).takeIf(File::isFile)?.let { f -> com.moyue.reader.feature.image.VisualRepository.hash(f) } }
        val input = targetContext.cacheDir.resolve("qa-v160-search.txt").apply { writeText("第一章 标注\n\n明月照天涯，星光入梦。\n\n第二段阅读内容。\n\n重复词重复词\n\n重复词") }
        val imported = c.importService.importDocument(android.net.Uri.fromFile(input), "V160验证文本.txt")
        verify(imported is ImportState.Completed, "TXT import works")
        val bookId = (imported as ImportState.Completed).bookId
        try {
            withTimeout(20000) { while (c.database.searchDao().state(bookId)?.status != "READY") delay(100) }
            verify(c.database.searchDao().search(SearchTerms.query("星光入梦"), "星光入梦", 50, 0).none { it.chunk.bookId == bookId }, "novel body is excluded from global search")
            val chapters = c.database.chapterDao().forBook(bookId)
            val chapter = com.moyue.reader.feature.reader.RoomReaderDataSource(c.database, c.storage).load(requireNotNull(c.database.bookDao().get(bookId)), chapters.first())
            val i = chapter.blocks.indexOfFirst { blockText(it).contains("明月照天涯") }
            val offset = blockText(chapter.blocks[i]).indexOf("明月照天涯")
            val draft = textDraft(bookId, chapter, ReaderPosition(i, offset), ReaderPosition(i, offset + 5))
            val id = c.annotations.save(draft, "这是本地笔记", "green")
            var annotation = c.database.annotationDao().forBook(bookId).first().first { it.id == id }
            verify(annotation.type == "NOTE" && annotation.selectedText == "明月照天涯", "annotation captures exact source quote")
            c.database.annotationDao().update(annotation.copy(note = "修改后的笔记", color = "blue"))
            annotation = c.database.annotationDao().forBook(bookId).first().first { it.id == id }
            verify(annotation.note == "修改后的笔记" && annotation.color == "blue", "annotation edit persists")
            verify(c.annotations.export(listOf(AnnotationItem(annotation, "V160验证文本"))).contains("> 明月照天涯"), "Markdown excerpt export")
            val model=com.moyue.reader.feature.reader.ReaderViewModel(com.moyue.reader.feature.reader.ReaderRepository(com.moyue.reader.feature.reader.RoomReaderDataSource(c.database,c.storage)))
            model.open(bookId)
            model.jumpToAnchor(JSONObject(draft.anchor).put("blockIndex",0).put("charOffset",0).put("quote","重复词").toString())
            verify(model.state.value?.error?.contains("无法唯一定位")==true,"changed anchor with repeated text is not guessed")
            verify(c.database.annotationDao().forBook(bookId).first().single().id==id,"unresolved source keeps original excerpt")
            val pdf = targetContext.cacheDir.resolve("qa-v160.pdf")
            context.assets.open("pdf-fixtures/pdf-basic.pdf").use { source -> pdf.outputStream().use { source.copyTo(it) } }
            var pdfPages = 0; var pdfText = 0
            PdfTextExtractor.extract(targetContext, pdf) { page, text -> pdfPages++; pdfText += text.length; sendStatus(1, Bundle().apply { putString("stream", "PDF extracted page ${page + 1}") }) }
            verify(pdfPages == 12 && pdfText > 100, "PDF.js background text extraction twelve pages")
            pdf.delete()
            originalBooks.forEach { b -> verify(c.database.bookDao().get(b.id) == b, "existing book record retained ${b.id}"); verify(originalHash[b.id] == File(b.sourcePath).takeIf(File::isFile)?.let { com.moyue.reader.feature.image.VisualRepository.hash(it) }, "existing source byte exact ${b.id}") }
        } finally {
            c.database.bookDao().get(bookId)?.let { c.database.bookDao().delete(it) }
            c.database.searchDao().prune(); c.storage.bookDirectory(bookId).deleteRecursively(); input.delete()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) } }
        uiAutomation.rootInActiveWindow?.let { walk(it) }
        uiAutomation.windows.mapNotNull { it.root }.forEach { walk(it) }; return result
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(80) { nodes().lastOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }; SystemClock.sleep(100) }
        error("UI missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun click(label: String, first: Boolean = false) {
        val node = waitFor(label).let { if(first)nodes().first {it.text?.toString()==label||it.contentDescription?.toString()==label} else it }
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);check(!bounds.isEmpty){"empty target $label"}
        val down=SystemClock.uptimeMillis()
        android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0).let {it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(it,true));it.recycle()}
        SystemClock.sleep(60)
        android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0).let {it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(it,true));it.recycle()}
        SystemClock.sleep(600)
    }
    private fun ui(c: MoyueContainer, onlyPdf: Boolean = false) {
        uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val activity=startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("摘录"); waitFor("搜索摘录、笔记或书名"); verify(true, "excerpt center visible")
        click("返回"); click("搜索书籍"); waitFor("全局搜索"); verify(true, "global search visible")
        val editable = nodes().first { it.isEditable }
        check(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "明月") }))
        SystemClock.sleep(1200); click("正文"); verify(true, "search tabs and Chinese input work")
        click("返回"); waitFor("墨阅"); verify(true, "back restores shelf")
        val ids=mutableListOf<Long>()
        val before=runBlocking {c.preferences.preferences.first()}
        fun open(title:String) {
            click("搜索书籍");waitFor("全局搜索")
            nodes().first {it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)})
            SystemClock.sleep(900);click(title);SystemClock.sleep(1800)
        }
        fun findWeb(v:android.view.View):android.webkit.WebView? {
            if(v is android.webkit.WebView)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)findWeb(v.getChildAt(i))?.let {return it};return null
        }
        fun js(code:String):String {
            val latch=java.util.concurrent.CountDownLatch(1);var value="null"
            runOnMainSync {checkNotNull(findWeb(activity.window.decorView)).let {it.requestFocus();it.evaluateJavascript(code){value=it;latch.countDown()}}}
            check(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));return value
        }
        fun saveNote(note:String) {
            waitFor("笔记（可选）")
            nodes().last {it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,note)})
            click("保存");SystemClock.sleep(500)
        }
        fun screenshot(name:String) {
            val file=File(targetContext.getExternalFilesDir(null),name)
            uiAutomation.takeScreenshot().let { image->file.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle() }
        }
        try {
            if(!onlyPdf) {
            val bookId=runBlocking {
                val input=targetContext.cacheDir.resolve("V160UI文本.txt").apply {writeText("第一章 月光\n\n明月照天涯，星光入梦。".repeat(40)+"\n\nV160正文跳转目标位于这里。")}
                val result=c.importService.importDocument(android.net.Uri.fromFile(input),"V160UI文本.txt") as ImportState.Completed
                c.database.bookDao().get(result.bookId)!!.let {c.database.bookDao().update(it.copy(title="V160UI文本"))};result.bookId
            };ids+=bookId
            open("V160UI文本")
            lateinit var textView:android.widget.TextView
            fun findText(v:android.view.View, needle:String="明月照天涯"):android.widget.TextView? {
                if(v is android.widget.TextView && v.text.toString().contains(needle))return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)findText(v.getChildAt(i),needle)?.let {return it};return null
            }
            var touchX=0f;var touchY=0f
            runOnMainSync {
                textView=checkNotNull(findText(activity.window.decorView));val start=textView.text.indexOf("明月照天涯")
                val origin=IntArray(2);textView.getLocationOnScreen(origin);val line=textView.layout.getLineForOffset(start)
                touchX=origin[0]+textView.layout.getPrimaryHorizontal(start+2);touchY=origin[1]+(textView.layout.getLineTop(line)+textView.layout.getLineBottom(line))/2f
            }
            val down=SystemClock.uptimeMillis()
            android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,touchX,touchY,0).let {it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(it,true));it.recycle()}
            SystemClock.sleep(800)
            android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,touchX,touchY,0).let {it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(it,true));it.recycle()}
            waitFor("笔记");verify(true,"native long press exposes annotation actions")
            runOnMainSync {val start=textView.text.indexOf("明月照天涯");android.text.Selection.setSelection(textView.text as android.text.Spannable,start,start+5)}
            click("笔记");saveNote("V160UI原生笔记")
            val native=runBlocking {c.database.annotationDao().forBook(bookId).first().single()}
            verify(native.selectedText=="明月照天涯" && native.note=="V160UI原生笔记","native TextView selection saves source offsets")
            screenshot("v160-native.png")
            runBlocking {c.preferences.update {before.copy(fontSizeSp=before.fontSizeSp+2)}};SystemClock.sleep(2000)
            runOnMainSync {verify(checkNotNull(findText(activity.window.decorView)).text.let {it is android.text.Spanned && it.getSpans(0,it.length,com.moyue.reader.feature.reader.ReaderHighlightSpan::class.java).isNotEmpty()},"highlight survives font reflow")}
            runBlocking {c.preferences.update {it.copy(pageMode=com.moyue.reader.core.settings.PageMode.PAGED)}};SystemClock.sleep(2000)
            runOnMainSync {verify(checkNotNull(findText(activity.window.decorView)).text.let {it is android.text.Spanned && it.getSpans(0,it.length,com.moyue.reader.feature.reader.ReaderHighlightSpan::class.java).isNotEmpty()},"saved highlight appears in paged mode")};screenshot("v160-paged.png")
            runBlocking {c.preferences.update {it.copy(pageMode=com.moyue.reader.core.settings.PageMode.SCROLL)}};SystemClock.sleep(1200)
            runOnMainSync {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};SystemClock.sleep(2200)
            verify(runBlocking {c.database.annotationDao().forBook(bookId).first().single().anchorJson}==native.anchorJson,"orientation does not mutate source anchor")
            runOnMainSync {verify(findText(activity.window.decorView)!=null,"orientation keeps reader open")}
            runOnMainSync {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};SystemClock.sleep(1500)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("墨阅")
            click("摘录");waitFor("V160UI原生笔记");click("返回原文",first=true);SystemClock.sleep(1600)
            runOnMainSync {verify(findText(activity.window.decorView)!=null,"excerpt returns to native original")};sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("墨阅")

            val catalogTitle = runBlocking { c.database.chapterDao().forBook(bookId).first().title }
            val catalogHit=runBlocking {withTimeout(20000) {var hit:com.moyue.reader.core.database.SearchHit?=null;while(hit==null){hit=c.database.searchDao().search(SearchTerms.query(catalogTitle),catalogTitle,20,0).firstOrNull {it.chunk.bookId==bookId};if(hit==null)delay(100)};requireNotNull(hit)}}
            click("搜索书籍");waitFor("全局搜索")
            nodes().first {it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,catalogTitle)})
            click("目录");click("V160UI文本 · ${catalogHit.chunk.location}");SystemClock.sleep(800)
            runOnMainSync {verify(findText(activity.window.decorView)!=null,"global catalog result opens its chapter")}
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor("墨阅")

            val mdId=runBlocking {
                val input=targetContext.cacheDir.resolve("V160UI文档.md").apply {writeText("# 标注测试\n\n明月**照天涯**，星光入梦。\n")}
                val result=c.importService.importDocument(android.net.Uri.fromFile(input),"V160UI文档.md") as ImportState.Completed
                c.database.bookDao().get(result.bookId)!!.let {c.database.bookDao().update(it.copy(title="V160UI文档"))};result.bookId
            };ids+=mdId
            val mdHash=runBlocking {com.moyue.reader.feature.image.VisualRepository.hash(File(c.database.bookDao().get(mdId)!!.sourcePath))}
            open("V160UI文档");waitFor("已保存")
            js("(()=>{const p=document.querySelector('#reading p'),w=document.createTreeWalker(p,NodeFilter.SHOW_TEXT),nodes=[];while(w.nextNode())nodes.push(w.currentNode);const r=document.createRange();r.setStart(nodes[0],0);r.setEnd(nodes.at(-1),nodes.at(-1).length);const s=getSelection();s.removeAllRanges();s.addRange(r);return true})()")
            SystemClock.sleep(600)
            sendStatus(1,Bundle().apply {putString("stream","Markdown selection="+js("JSON.stringify({quote:getSelection().toString(),collapsed:getSelection().isCollapsed,bars:[...document.querySelectorAll('[data-moyue-ui]')].map(x=>x.style.display)})"))})
            click("笔记");saveNote("V160UI Markdown笔记")
            val md=runBlocking {c.database.annotationDao().forBook(mdId).first().single()}
            verify(md.selectedText.contains("明月照天涯") && JSONObject(md.anchorJson).optString("kind")=="MARKDOWN","Markdown DOM selection spans formatting")
            verify(js("document.querySelectorAll('#reading mark[data-moyue-mark]').length").toInt()>0,"Markdown saved mark rendered")
            verify(mdHash==runBlocking {com.moyue.reader.feature.image.VisualRepository.hash(File(c.database.bookDao().get(mdId)!!.sourcePath))},"Markdown source untouched by marks")
            screenshot("v160-markdown.png")
            click("返回");waitFor("墨阅")
            }

            val pdfId=runBlocking {
                val input=targetContext.cacheDir.resolve("V160UI.pdf");context.assets.open("pdf-fixtures/pdf-basic.pdf").use {source->input.outputStream().use {source.copyTo(it)}}
                val result=c.importService.importDocument(android.net.Uri.fromFile(input),"V160UI.pdf") as ImportState.Completed
                c.database.bookDao().get(result.bookId)!!.let {c.database.bookDao().update(it.copy(title="V160UI PDF"))};result.bookId
            };ids+=pdfId;open("V160UI PDF");waitFor("PDF 菜单")
            repeat(50) {if(js("document.querySelectorAll('.textLayer span').length").toInt()>0)return@repeat;SystemClock.sleep(100)}
            js("(()=>{const n=[...document.querySelectorAll('.textLayer span')].find(x=>{const r=x.getBoundingClientRect();return x.firstChild?.nodeType===3&&x.textContent.trim().length>3&&r.top>110&&r.bottom<innerHeight-130}).firstChild,r=document.createRange();r.setStart(n,0);r.setEnd(n,Math.min(n.length,8));getSelection().removeAllRanges();getSelection().addRange(r);return true})()")
            SystemClock.sleep(600);click("笔记");saveNote("V160UI PDF笔记")
            val pdf=runBlocking {c.database.annotationDao().forBook(pdfId).first().single()}
            verify(JSONObject(pdf.anchorJson).getJSONArray("pageRects").length()>0,"PDF selected text stores page coordinates")
            var rendered=false
            for(attempt in 0 until 100){if(js("document.querySelectorAll('.moyue-highlight-layer').length").toInt()>0){rendered=true;break};SystemClock.sleep(100)}
            if(!rendered){screenshot("v160-pdf-failure.png");sendStatus(1,Bundle().apply {putString("stream","PDF state="+js("JSON.stringify({document:!!PDFViewerApplication.pdfDocument,pages:PDFViewerApplication.pagesCount,div:!!PDFViewerApplication.pdfViewer.getPageView(0)?.div})")+" UI="+nodes().mapNotNull {it.text?:it.contentDescription})})}
            verify(rendered,"PDF mark rendered")
            js("document.querySelector('.moyue-highlight-layer > div').scrollIntoView({block:'center'})");SystemClock.sleep(400)
            verify(js("(()=>{const r=document.querySelector('.moyue-highlight-layer > div').getBoundingClientRect();return r.width>0&&r.height>0&&r.top>100&&r.bottom<innerHeight-130})()") == "true","PDF highlight is visible within viewport")
            screenshot("v160-pdf.png")
            val zoomAlignment=js("(()=>{const app=PDFViewerApplication;app.pdfViewer.updateScale({steps:1,drawingDelay:600});const page=app.pdfViewer.getPageView(0),after=document.querySelector('.moyue-highlight-layer > div').getBoundingClientRect(),rect=JSON.parse('${pdf.anchorJson.replace("'", "\\'")}').pageRects[0],p1=page.viewport.convertToViewportPoint(rect[0],rect[1]),p2=page.viewport.convertToViewportPoint(rect[2],rect[3]),bounds=page.div.getBoundingClientRect();return Math.abs(after.width-Math.abs(p2[0]-p1[0]))<2&&Math.abs(after.left-bounds.left-Math.min(p1[0],p2[0]))<2})()")
            verify(zoomAlignment=="true","PDF mark stays attached during delayed zoom before canvas rerender")
            SystemClock.sleep(1000)
            verify(js("document.querySelectorAll('.moyue-highlight-layer').length").toInt()>0,"PDF marks survive zoom")
            click("返回书架");waitFor("墨阅")
        } finally {runBlocking {
            c.preferences.update {before}
            for(id in ids){c.database.bookDao().get(id)?.let {c.database.bookDao().delete(it)};c.storage.bookDirectory(id).deleteRecursively()}
            c.database.searchDao().prune()
        } }
    }
}
