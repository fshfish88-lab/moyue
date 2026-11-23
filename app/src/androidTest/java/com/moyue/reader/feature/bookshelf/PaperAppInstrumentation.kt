package com.moyue.reader.feature.bookshelf

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import com.moyue.reader.*
import com.moyue.reader.core.model.*
import com.moyue.reader.core.settings.*
import com.moyue.reader.core.ui.*
import com.moyue.reader.feature.annotations.*
import com.moyue.reader.feature.importbook.ImportState
import com.moyue.reader.feature.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tests native navigation, real persistence and editor mode changes using owned fixtures only. */
class PaperAppInstrumentation : Instrumentation() {
    private var suite = "main"
    private var checks = 0
    private lateinit var activity: MainActivity
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite") ?: "main"; super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        try {
            lateinit var c: MoyueContainer
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            if (suite == "reader-feedback") readerFeedback(c) else exercise(c)
            out.putString("stream", "PASS paper app $suite checks=$checks"); finish(-1, out)
        } catch (e: Throwable) {
            runCatching { screenshot("failure-$suite") }
            out.putString("stream", "FAIL paper app $suite checks=$checks ${e.stackTraceToString()} UI=${nodes().mapNotNull { it.text ?: it.contentDescription }.take(80)}")
            finish(0, out)
        }
    }
    private fun verify(value: Boolean, label: String) { check(value) { label }; checks++; sendStatus(1, Bundle().apply { putString("stream", "check $checks: $label") }) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        uiAutomation.rootInActiveWindow?.let(::walk); return all
    }
    private fun find(label: String) = nodes().lastOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    private fun waitFor(label: String, scroll: Boolean = false): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            find(label)?.let { return it }
            if (scroll && attempt % 12 == 8) nodes().firstOrNull { n -> n.isScrollable && n.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Missing $label")
    }
    private fun touch(x: Float, y: Float, hold: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(if (hold) 800 else 60)
        }
        SystemClock.sleep(500)
    }
    private fun tap(label: String, scroll: Boolean = false) { val r = Rect(); waitFor(label, scroll).getBoundsInScreen(r); check(!r.isEmpty); touch(r.exactCenterX(), r.exactCenterY()) }
    private fun bounds(label: String) = Rect().also { waitFor(label).getBoundsInScreen(it) }
    private fun swipeUp() {
        val screen = activity.window.decorView
        val start = SystemClock.uptimeMillis()
        repeat(10) { step ->
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 9 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                screen.width * .5f, screen.height * (.71f - step * .043f), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(12)
        }
        SystemClock.sleep(680)
    }
    private fun input(text: String) {
        val edit = nodes().first { it.isEditable }
        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        SystemClock.sleep(500)
    }
    private fun back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(600) }
    private fun screenshot(name: String) {
        SystemClock.sleep(650)
        val bitmap = requireNotNull(uiAutomation.takeScreenshot())
        targetContext.getExternalFilesDir(null)!!.resolve("paper-full-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun launch() {
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("墨阅"); SystemClock.sleep(800)
    }
    private fun js(script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        runOnMainSync {
            fun search(view: View): WebView? {
                if (view is WebView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
                return null
            }
            requireNotNull(search(activity.window.decorView)).evaluateJavascript(script) { result = it; latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun awaitJs(script: String) { repeat(100) { if (js(script) == "true") return; SystemClock.sleep(100) }; error("Web engine not ready: $script") }
    private fun open(title: String) { tap(title); waitFor("书籍详情"); tap(if (title == "纸境验收图片") "查看图片" else "开始阅读"); SystemClock.sleep(900) }
    private fun showReader() {
        if (find("返回书架") == null) {
            val dm = targetContext.resources.displayMetrics
            touch(dm.widthPixels * .5f, dm.heightPixels * .52f)
        }
        waitFor("返回书架")
    }
    private fun readerFeedback(c: MoyueContainer) {
        val saved = runBlocking { c.preferences.preferences.first() }
        val savedShelf = runBlocking { c.shelfPreferences.preferences.first() }
        val file = targetContext.cacheDir.resolve("reader-feedback.txt").apply {
            writeText((1..14).joinToString("\n\n") { chapter ->
                "第${chapter}章 反馈测试\n\n" + (1..22).joinToString("\n\n") { block ->
                    "第${chapter}章正文第${block}段。" + "这里是一段用于检查章节滚动的文字。".repeat(3)
                }
            })
        }
        val fixture = runBlocking { c.importService.importDocument(Uri.fromFile(file), file.name) as ImportState.Completed }
        try {
            runBlocking {
                c.preferences.update { it.copy(pageMode = PageMode.SCROLL, autoNextChapter = true) }
                c.shelfPreferences.update { ShelfPreferences(sort = ShelfSort.IMPORTED) }
                val chapter = ReaderRepository(RoomReaderDataSource(c.database, c.storage)).open(fixture.bookId).chapter
                val block = chapter.blocks.indexOfFirst { it is ContentBlock.Text && it.text.length >= 8 }
                c.annotations.save(textDraft(fixture.bookId, chapter, ReaderPosition(block, 0), ReaderPosition(block, 8)), "", "blue")
            }
            val title = runBlocking { requireNotNull(c.database.bookDao().get(fixture.bookId)).title }
            launch()
            tap("摘录")
            waitFor("摘录与笔记")
            screenshot("feedback-annotations")
            tap("书架")
            open(title)
            showReader()
            waitFor("本章进度 0%")
            repeat(15) {
                if (find("本章进度 100%") == null) swipeUp()
            }
            verify(find("本章进度 100%") != null && find("第2章 反馈测试") == null,
                "a fast swipe reaches 100% without opening the next chapter")
            screenshot("feedback-chapter-end")
            runBlocking { c.preferences.update { it.copy(autoNextChapter = false) } }
            SystemClock.sleep(400)
            swipeUp()
            verify(find("本章进度 100%") != null && find("第2章 反馈测试") == null,
                "turning off automatic continuation keeps the reader at the chapter end")
            runBlocking { c.preferences.update { it.copy(autoNextChapter = true) } }
            SystemClock.sleep(400)
            swipeUp()
            waitFor("第2章 反馈测试")
            verify(find("本章进度 0%") != null && find("第3章 反馈测试") == null,
                "a fresh swipe from the bottom opens just the next chapter at its start")
            tap("目录")
            val header = "目录 · 共 14 章"
            waitFor(header)
            screenshot("feedback-catalog-before-scroll")
            val headerTop = bounds(header).top
            repeat(4) { swipeUp() }
            waitFor("第14章 反馈测试")
            // Push against the bottom after the list has settled, outside the opening animation.
            val beforeOverscroll = bounds(header).top
            swipeUp()
            val afterOverscroll = bounds(header).top
            verify(kotlin.math.abs(headerTop - beforeOverscroll) <= 2 &&
                kotlin.math.abs(beforeOverscroll - afterOverscroll) <= 2,
                "the chapter list scrolls to the end without moving its panel ($headerTop, $beforeOverscroll, $afterOverscroll)")
            tap("定位当前章")
            waitFor("第2章 反馈测试")
            screenshot("feedback-catalog")
            tap("第3章 反馈测试")
            waitFor("第3章 反馈测试")
            waitFor("本章进度 0%")
            verify(find("关闭目录") == null, "selecting a chapter closes the catalog and opens its start")
            tap("搜索书籍")
            val searchTitle = bounds("全局搜索")
            // Compare the chip surfaces, since glyphs need not be centered in their text boxes.
            val firstChip = Rect().also { requireNotNull(waitFor("全部").parent).getBoundsInScreen(it) }
            val lastChip = Rect().also { requireNotNull(waitFor("正文").parent).getBoundsInScreen(it) }
            val chipsCenter = (firstChip.left + lastChip.right) / 2
            val center = activity.window.decorView.width / 2
            verify(kotlin.math.abs(searchTitle.centerX() - center) <= 4 && kotlin.math.abs(chipsCenter - center) <= 4,
                "search title and filter group are centered (title=${searchTitle.centerX()}, chips=$chipsCenter, screen=$center)")
            screenshot("feedback-search")
        } finally {
            file.delete()
            runBlocking {
                c.preferences.update { saved }
                c.shelfPreferences.update { savedShelf }
                c.database.bookDao().get(fixture.bookId)?.let { c.database.bookDao().delete(it) }
                c.storage.bookDirectory(fixture.bookId).deleteRecursively()
                c.database.searchDao().prune()
            }
        }
    }
    private fun exercise(c: MoyueContainer) {
        val savedReader = runBlocking { c.preferences.preferences.first() }
        val savedShelf = runBlocking { c.shelfPreferences.preferences.first() }
        val ids = mutableListOf<Long>()
        var shelf: Long? = null; var tag: Long? = null
        try {
            runBlocking {
                check(c.database.bookDao().observeAll().first().none { it.title == "纸境验收小说" }) { "Fixture title already exists; refusing to touch it" }
                c.preferences.update { it.copy(theme = ReaderTheme.SEPIA, fontFamily = ReaderFont.SERIF, pageMode = PageMode.SCROLL, customColors = false) }
                c.shelfPreferences.update { ShelfPreferences(sort = ShelfSort.IMPORTED) }
                val file = targetContext.cacheDir.resolve("paper-full-novel.txt").apply {
                    writeText((1..3).joinToString("\n") { "第${it}章 山风经过\n\n" + "清晨，山谷还在薄雾里。山风翻过一页书，心也慢慢安静下来。\n\n".repeat(50) })
                }
                val txt = c.importService.importDocument(Uri.fromFile(file), file.name) as ImportState.Completed; ids += txt.bookId
                c.database.bookDao().get(txt.bookId)!!.let { book ->
                    val cover = GeneratedCoverFileWriter().generate("山海之间", "纸境验收", c.storage.bookDirectory(txt.bookId).resolve("qa-cover.png"))
                    c.database.bookDao().update(book.copy(title = "纸境验收小说", author = "纸境验收", coverPath = cover.absolutePath, lastReadAt = System.currentTimeMillis() + 10000))
                }
                val mdFile = targetContext.cacheDir.resolve("paper-full-notes.md").apply { writeText("# 设计随记\n\n让阅读慢下来。记录灵感，也记录此刻的想法。\n\n## 关于留白\n\n让内容有呼吸的空间。\n") }
                val md = c.importService.importDocument(Uri.fromFile(mdFile), mdFile.name) as ImportState.Completed; ids += md.bookId
                c.database.bookDao().get(md.bookId)!!.let { c.database.bookDao().update(it.copy(title = "纸境设计随记")) }
                for ((asset, title) in listOf("pdf-fixtures/pdf-basic.pdf" to "纸境旅行札记", "visual-fixtures/comic.cbz" to "纸境长街纪事", "visual-fixtures/image.png" to "纸境验收图片")) {
                    val f = targetContext.cacheDir.resolve("paper-full-${asset.substringAfterLast('/')}")
                    context.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
                    val result = c.importService.importDocument(Uri.fromFile(f), f.name) as ImportState.Completed; ids += result.bookId
                    c.database.bookDao().get(result.bookId)!!.let { c.database.bookDao().update(it.copy(title = title)) }
                }
                val now = System.currentTimeMillis()
                ids.forEachIndexed { index, id -> c.database.bookDao().get(id)!!.let { c.database.bookDao().update(it.copy(createdAt = now + 100 - index)) } }
                shelf = c.shelves.saveCategory(false, null, "UI验收书架")
                tag = c.shelves.saveCategory(true, null, "UI验收标签")
                c.shelves.categories(setOf(ids[0]), false, setOf(shelf!!), emptySet())
                val chapter = ReaderRepository(RoomReaderDataSource(c.database, c.storage)).open(ids[0]).chapter
                val block = chapter.blocks.indexOfFirst { it is ContentBlock.Text && it.text.length >= 20 }
                check(block >= 0)
                c.annotations.save(textDraft(ids[0], chapter, ReaderPosition(block, 0), ReaderPosition(block, 8)), "山风经过，适合慢慢读。", "green")
                c.annotations.save(textDraft(ids[0], chapter, ReaderPosition(block, 9), ReaderPosition(block, 16)), "", "yellow")
            }
            launch()
            if (suite == "docs") { documents(c, ids); return }
            if (suite == "responsive") {
                screenshot("responsive-shelf"); tap("纸境验收小说"); screenshot("responsive-detail")
                tap("开始阅读"); showReader(); tap("目录"); waitFor("关闭目录"); screenshot("responsive-catalog"); tap("关闭目录")
                tap("阅读设置"); waitFor("关闭阅读设置"); screenshot("responsive-settings"); tap("关闭阅读设置")
                verify(true, "detail, full-width catalog and settings remain accessible at resized window")
                return
            }
            screenshot("01")
            tap("纸境验收小说"); waitFor("书籍详情"); screenshot("02"); tap("收藏")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id == ids[0] }.management.single().favorite }, "detail favorite persists independently")
            tap("管理书籍"); waitFor("管理书籍"); screenshot("23")
            tap("在读"); tap("标签管理"); tap("UI验收标签"); tap("应用")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id == ids[0] }.tags.any { it.id == tag } }, "detail tag assignment uses existing repository")
            tap("系列信息"); input("纸境系列"); tap("保存"); tap("完成")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id == ids[0] }.management.single().series == "纸境系列" }, "series information persists")
            tap("删除书籍"); screenshot("24"); tap("取消")
            verify(runBlocking { c.database.bookDao().get(ids[0]) != null }, "delete cancellation preserves book")
            tap("开始阅读"); showReader(); screenshot("03")
            tap("书签"); waitFor("保存书签"); tap("保存")
            tap("目录"); waitFor("关闭目录"); screenshot("05"); tap("书签"); screenshot("05-bookmarks"); tap("关闭目录")
            verify(runBlocking { c.database.annotationDao().forBook(ids[0]).first().any { it.type == "BOOKMARK" } }, "native bookmark appears in unified catalog")
            tap("阅读设置"); waitFor("关闭阅读设置"); screenshot("04")
            tap("主题 · 夜间"); tap("关闭阅读设置"); screenshot("16")
            tap("阅读设置"); tap("主题 · 纸色"); tap("排版"); screenshot("22"); tap("关闭阅读设置")
            tap("搜索书籍"); waitFor("全局搜索"); input("纸境验收小说"); screenshot("06"); tap("返回"); waitFor("墨阅")
            tap("添加内容"); waitFor("导入文件"); screenshot("07"); input("not-a-web-address"); tap("导入书架并阅读"); waitFor("暂时无法导入"); screenshot("18"); tap("关闭导入")
            verify(true, "import error retains URL and retry UI without external site requests")
            tap("墨阅"); waitFor("我的书库"); screenshot("11"); tap("我的书架"); waitFor("书架与分类"); screenshot("08"); tap("返回"); waitFor("墨阅")
            tap("筛选书籍"); screenshot("12"); tap("更多"); screenshot("20"); tap("仅收藏")
            val apply = nodes().last { it.text?.toString()?.contains("应用筛选") == true }; val r = Rect(); apply.getBoundsInScreen(r); touch(r.exactCenterX(), r.exactCenterY())
            verify(runBlocking { c.shelfPreferences.preferences.first().favoritesOnly }, "filter changes apply only on confirmation")
            runBlocking { c.shelfPreferences.update { ShelfPreferences(sort = ShelfSort.IMPORTED) } }; launch()
            tap("摘录"); waitFor("摘录与笔记"); screenshot("09")
            verify(runBlocking { c.annotations.export(c.database.annotationDao().observeAll().first().filter { it.annotation.bookId == ids[0] }).contains("山风经过") }, "annotation export retains fixture note")
            tap("设置"); waitFor("阅读与导入"); screenshot("10"); tap("导入偏好"); waitFor("自动检测编码"); screenshot("21")
            val encoding = runBlocking { c.preferences.preferences.first().autoDetectEncoding }; tap("自动检测编码")
            verify(runBlocking { c.preferences.preferences.first().autoDetectEncoding != encoding }, "import preference switch writes real DataStore")
            tap("返回"); tap("书架"); waitFor("墨阅"); tap("多选"); tap("纸境验收小说"); tap("纸境设计随记"); waitFor("已选 2 本"); screenshot("19"); tap("删除"); screenshot("24-batch"); tap("取消"); tap("取消")
            verify(runBlocking { ids.all { c.database.bookDao().get(it) != null } }, "bulk delete cancellation preserves all fixture books")
        } catch (e: Throwable) {
            runCatching { screenshot("failure-$suite-before-cleanup") }; throw e
        } finally {
            runBlocking {
                c.preferences.update { savedReader }; c.shelfPreferences.update { savedShelf }
                for (id in ids) { c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }; c.storage.bookDirectory(id).deleteRecursively() }
                shelf?.let { c.shelves.deleteCategory(false, it) }; tag?.let { c.shelves.deleteCategory(true, it) }
                c.database.searchDao().prune()
            }
        }
    }
    private fun documents(c: MoyueContainer, ids: List<Long>) {
        open("纸境旅行札记"); waitFor("PDF 菜单"); awaitJs("!!globalThis.PDFViewerApplication?.pdfDocument")
        screenshot("13"); val scale = js("PDFViewerApplication.pdfViewer.currentScale").toFloat()
        tap("PDF 菜单"); tap("放大"); SystemClock.sleep(800)
        verify(js("PDFViewerApplication.pdfViewer.currentScale").toFloat() > scale, "PDF zoom action still changes renderer scale")
        tap("返回书架"); waitFor("墨阅")
        open("纸境设计随记"); waitFor("即时编辑"); screenshot("14"); tap("源码")
        awaitJs("!!document.querySelector('.vditor-sv') && getComputedStyle(document.querySelector('.vditor-sv')).display !== 'none'")
        verify(true, "Markdown source tab reaches actual source editor")
        screenshot("14-source"); tap("即时编辑")
        awaitJs("!!document.querySelector('.vditor-ir') && getComputedStyle(document.querySelector('.vditor-ir')).display !== 'none'")
        verify(true, "Markdown instant tab switches actual editor mode")
        tap("阅读"); SystemClock.sleep(800); tap("返回"); waitFor("墨阅")
        open("纸境长街纪事"); waitFor("图片漫画菜单"); screenshot("15"); tap("更多"); tap("旋转90°"); tap("返回书架"); waitFor("墨阅")
        verify(runBlocking { c.database.documentDao().state(ids[3])?.positionJson?.let { JSONObject(it).optInt("rotation") == 90 } == true }, "comic rotation state persists through existing engine")
        open("纸境验收图片"); waitFor("图片漫画菜单"); screenshot("15-image"); tap("返回书架"); waitFor("墨阅")
        var add = false
        runOnMainSync { activity.setContent { MoyueTheme {
            BookshelfScreen(emptyList(), ShelfLibrary(), ShelfPreferences(), c.shelves, {}, {}, { emptySet() }, {}, {}, { add = true }, {})
        } } }
        waitFor("书架还是空的"); screenshot("17"); tap("添加第一本书"); verify(add, "empty shelf import action is connected without clearing real library")
    }
}
