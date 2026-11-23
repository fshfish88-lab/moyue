package com.moyue.reader.feature.bookshelf

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moyue.reader.MainActivity
import com.moyue.reader.MoyueApplication
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.document.DocumentFormat
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Exercises relocated shelf controls against real repositories; all fixtures are removed by ID. */
class PaperShelfInstrumentation : Instrumentation() {
    private var mode = "ui"
    private var checks = 0
    override fun onCreate(arguments: Bundle?) { mode = arguments?.getString("mode") ?: "ui"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            lateinit var c: MoyueContainer
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            exercise(c)
            result.putString("stream", "PASS paper shelf $mode checks=$checks"); finish(-1, result)
        } catch (e: Throwable) { result.putString("stream", "FAIL paper shelf $mode: ${e.stackTraceToString()}"); finish(0, result) }
    }
    private fun verify(value: Boolean, label: String) { check(value) { label }; checks++; sendStatus(1, Bundle().apply { putString("stream", "check $checks: $label") }) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        uiAutomation.rootInActiveWindow?.let(::walk); return all
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(100) { nodes().lastOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }; SystemClock.sleep(100) }
        error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun tap(label: String, hold: Boolean = false) {
        val r = Rect(); waitFor(label).getBoundsInScreen(r); check(!r.isEmpty)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(if (hold) 800 else 60)
        }
        SystemClock.sleep(500)
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(700)
        val image = requireNotNull(uiAutomation.takeScreenshot())
        targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun launch() { startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); waitFor("墨阅"); SystemClock.sleep(1200) }
    private fun back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(600) }
    private fun exercise(c: MoyueContainer) {
        val saved = runBlocking { c.shelfPreferences.preferences.first() }
        val ids = mutableListOf<Long>()
        var shelf: Long? = null
        try {
            runBlocking {
                c.shelfPreferences.update { ShelfPreferences(sort = ShelfSort.IMPORTED) }
                for ((index, title) in listOf("山海之间", "时间的纹理", "远方来信").withIndex()) {
                    val file = targetContext.cacheDir.resolve("纸境验收-$index.txt").apply { writeText("第一章 山风经过\n\n" + "清晨，山谷还在薄雾里。窗外的树梢微微摇晃，一阵风穿过旧木窗，把桌上的书翻到了新的一页。\n\n".repeat(80)) }
                    val imported = c.importService.importDocument(android.net.Uri.fromFile(file), file.name) as ImportState.Completed
                    ids += imported.bookId
                    c.database.bookDao().get(imported.bookId)!!.let { c.database.bookDao().update(it.copy(title = title, author = "纸境验收", createdAt = System.currentTimeMillis() + 100 - index,
                        lastReadAt = if (index == 0) System.currentTimeMillis() + 10000 else null, progress = if (index == 0) .48f else 0f)) }
                }
                for ((asset, title) in listOf("pdf-fixtures/pdf-basic.pdf" to "星河图鉴", "visual-fixtures/comic.cbz" to "长街纪事")) {
                    val file = targetContext.cacheDir.resolve("纸境验收-${asset.substringAfterLast('/')}")
                    context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                    val imported = c.importService.importDocument(android.net.Uri.fromFile(file), file.name) as ImportState.Completed
                    ids += imported.bookId
                    c.database.bookDao().get(imported.bookId)!!.let { c.database.bookDao().update(it.copy(title = title, createdAt = System.currentTimeMillis() - 100)) }
                }
                val file = targetContext.cacheDir.resolve("纸境验收.md").apply { writeText("# 设计随记\n\n记录灵感，让思考慢下来。") }
                val md = c.importService.importDocument(android.net.Uri.fromFile(file), file.name) as ImportState.Completed
                ids += md.bookId
                c.database.bookDao().get(md.bookId)!!.let { c.database.bookDao().update(it.copy(title = "设计随记", createdAt = System.currentTimeMillis() - 101)) }
                // Stable fixture order and honest generated covers; application code never receives demo data.
                val order = listOf(ids[0], ids[1], ids[2], ids[3], ids[5], ids[4])
                val now = System.currentTimeMillis()
                for ((index, id) in order.withIndex()) {
                    val book = c.database.bookDao().get(id)!!
                    val cover = if (index < 3) com.moyue.reader.core.ui.GeneratedCoverFileWriter().generate(book.title, book.author,
                        c.storage.bookDirectory(id).resolve("paper-qa-cover.png")).absolutePath else book.coverPath
                    c.database.bookDao().update(book.copy(createdAt = now + 100 - index, coverPath = cover))
                }
                shelf = c.shelves.saveCategory(false, null, "纸境验收书架-${System.nanoTime()}")
                c.shelves.categories(setOf(ids[0]), false, setOf(shelf!!), emptySet())
            }
            launch()
            screenshot("paper-shelf-$mode.png")
            verify(waitFor("搜索书籍").isVisibleToUser && waitFor("添加内容").isVisibleToUser, "header actions accessible")
            verify(waitFor("书架").isVisibleToUser && waitFor("摘录").isVisibleToUser && waitFor("设置").isVisibleToUser, "three navigation targets visible")
            if (mode == "responsive") {
                // The entire content scrolls on short windows; offscreen tiles remain reachable.
                val scrollable = nodes().firstOrNull { it.isScrollable }
                verify(scrollable != null, "scrollable grid survives resized window")
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(600); screenshot("paper-shelf-responsive-scrolled.png")
                return
            }
            tap("文档"); verify(runBlocking { c.shelfPreferences.preferences.first().formats == setOf(DocumentFormat.PDF, DocumentFormat.MARKDOWN) }, "documents chip writes existing filter preferences")
            waitFor("星河图鉴"); verify(nodes().none { it.text?.toString() == "时间的纹理" }, "document filter excludes TXT tiles")
            tap("漫画"); waitFor("长街纪事"); verify(runBlocking { c.shelfPreferences.preferences.first().formats == setOf(DocumentFormat.COMIC) }, "comic filter maps to document registry")
            tap("全部"); waitFor("时间的纹理")
            tap("时间的纹理"); waitFor("书籍详情"); verify(true, "cover opens existing detail destination"); back(); waitFor("墨阅")
            tap("继续阅读"); waitFor("第一章 山风经过")
            tap("清晨，山谷还在薄雾里。窗外的树梢微微摇晃，一阵风穿过旧木窗，把桌上的书翻到了新的一页。")
            waitFor("返回书架"); verify(true, "continue opens native reader and retains toolbar toggle"); tap("返回书架"); waitFor("墨阅")
            tap("时间的纹理", true); waitFor("加入书架"); waitFor("标签管理"); waitFor("系列信息"); verify(true, "long press retains management actions"); back()
            tap("多选"); tap("时间的纹理"); waitFor("已选 1 本"); tap("远方来信"); waitFor("已选 2 本")
            waitFor("取消收藏"); waitFor("状态"); waitFor("书架"); waitFor("标签"); waitFor("删除"); verify(true, "bulk actions retained"); screenshot("paper-shelf-selection.png"); tap("取消")
            tap("我的书籍"); waitFor("管理书架"); waitFor("管理标签"); tap("阅读状态与收藏"); waitFor("想读"); waitFor("在读"); waitFor("读完"); waitFor("仅收藏"); verify(true, "relocated state and category controls reachable"); back()
            tap("筛选书籍"); waitFor("筛选书籍"); tap("更多"); waitFor("仅收藏"); verify(true, "existing advanced filters reachable"); back()
            tap("添加内容"); waitFor("导入文件"); waitFor("新建 Markdown"); waitFor("网页地址"); verify(true, "existing import sheet reachable"); back()
            tap("搜索书籍"); waitFor("全局搜索"); verify(true, "global search navigation retained"); back()
            tap("摘录"); waitFor("全部类型"); verify(true, "annotation center navigation retained"); back()
            tap("设置"); waitFor("阅读与导入"); verify(true, "settings navigation retained"); back()
            screenshot("paper-shelf-ui-final.png")
        } finally {
            runBlocking {
                c.shelfPreferences.update { saved }
                for (id in ids) { c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }; c.storage.bookDirectory(id).deleteRecursively() }
                shelf?.let { c.shelves.deleteCategory(false, it) }
                c.database.searchDao().prune()
            }
        }
    }
}
