package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import com.moyue.reader.*
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File

/** Imports temporary fixtures; original books, source files, preferences and progress are preserved. */
class ReaderControlsInstrumentation : Instrumentation() {
    private var label = "controls"
    private var formatOnly: String? = null
    private var textTarget = false
    private var pageMode = PageMode.SCROLL
    private var cleanupIds = emptyList<Long>()
    private var checks = 0
    private fun report(value: String) = sendStatus(1, Bundle().apply { putString("stream", value) })
    private fun verify(ok: Boolean, value: String) { check(ok) { value }; checks++; report("PASS $checks: $value") }
    override fun onCreate(arguments: Bundle?) { label = arguments?.getString("label") ?: label; formatOnly = arguments?.getString("format"); cleanupIds = arguments?.getString("cleanupIds")?.split(",")?.map { it.toLong() } ?: emptyList(); textTarget = arguments?.getString("target") == "text"; pageMode = if (arguments?.getString("mode") == "paged") PageMode.PAGED else PageMode.SCROLL; super.onCreate(arguments); start() }
    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(textViews(view.getChildAt(i)))
    }
    override fun onStart() {
        val result = Bundle()
        lateinit var container: MoyueContainer
        val fixtureIds = mutableListOf<Long>()
        var initialized = false
        var success = false
        try {
            runOnMainSync { container = (targetContext.applicationContext as MoyueApplication).container }
            initialized = true
            if (cleanupIds.isNotEmpty()) {
                runBlocking { cleanupIds.forEach { id ->
                    val book = checkNotNull(container.database.bookDao().get(id))
                    check(book.lastReadAt == null && book.progress == 0f)
                    check(book.title in setOf("诡秘之主", "工具栏回归"))
                    container.database.bookDao().delete(book)
                    container.storage.bookDirectory(id).deleteRecursively()
                }; container.database.searchDao().prune() }
                finish(-1, Bundle().apply { putString("stream", "PASS removed explicit interrupted-test IDs $cleanupIds") })
                return
            }
            runBlocking {
                val epub = File(targetContext.getExternalFilesDir(null), "controls-real.epub")
                check(epub.isFile) { "Push the supplied EPUB to controls-real.epub first" }
                fixtureIds += (container.importService.importDocument(android.net.Uri.fromFile(epub), "工具栏回归.epub") as ImportState.Completed).bookId
                val txt = File(targetContext.cacheDir, "工具栏回归.txt").apply { writeText("第一章\n\n" + "星光照向远处，阅读仍在原来的位置。".repeat(80)) }
                fixtureIds += (container.importService.importDocument(android.net.Uri.fromFile(txt), txt.name) as ImportState.Completed).bookId
                txt.delete()
            }
            val books = runBlocking { container.database.bookDao().observeAll().first() }
            val prefs = runBlocking { container.preferences.preferences.first() }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            for (format in listOf(SourceType.EPUB, SourceType.TXT)) {
                if (formatOnly != null && formatOnly != format.name) continue
                val book = books.first { it.sourceType == format && it.id in fixtureIds }
                val chapters = runBlocking { container.database.chapterDao().forBook(book.id) }
                val indexes = if (format == SourceType.EPUB && textTarget) {
                    listOf(chapters.indexOfFirst { it.title.contains("第一章") }.takeIf { it >= 0 } ?: 4)
                } else if (format == SourceType.EPUB) listOf(0, minOf(3, chapters.lastIndex)) else listOf(0)
                for (index in indexes) {
                    val chapter = runBlocking { RoomReaderDataSource(container.database, container.storage).load(book, chapters[index]) }
                    val annotations = runBlocking { container.database.annotationDao().forBook(book.id).first() }
                    val startBlock = if (textTarget) chapter.blocks.indexOfFirst { com.moyue.reader.feature.annotations.blockText(it).length > 40 }.coerceAtLeast(0) else 0
                    val reader = mutableStateOf(ReaderState(book.id, book.title, chapter, chapters, ReaderPosition(startBlock, 0), 0f, 0f, false, prefs.copy(pageMode = pageMode), false, false))
                    val pageProgress = java.util.concurrent.atomic.AtomicReference(0f)
                    val progressHistory = java.util.Collections.synchronizedList(mutableListOf<Float>())
                    runOnMainSync { activity.setContent { MaterialTheme {
                        val current = reader.value
                        androidx.compose.runtime.key(book.id, index, pageMode) {
                        ReaderScreen(current, onBack = {}, onToggleControls = { reader.value = reader.value.copy(controlsVisible = !reader.value.controlsVisible) },
                            onPrevious = {}, onNext = {}, onChapter = {}, onPosition = { position, progress -> pageProgress.set(progress); progressHistory.add(progress); reader.value = reader.value.copy(position = position, chapterProgress = progress) }, onPreferences = { reader.value = reader.value.copy(preferences = it) },
                            onScrollPosition = { _, _, _ -> }, onPrefetch = {}, annotations = annotations + emptyList(),
                            onSelection = { _, _, _, _ -> check(current.bookId == book.id) })
                        }
                    } } }
                    SystemClock.sleep(1600)
                    val name = "${format.name}-$index-$pageMode"
                    val snapshots = mutableListOf<Triple<TextView, CharSequence, android.text.Layout>>()
                    runOnMainSync { textViews(activity.window.decorView).forEach { view -> view.layout?.let { snapshots += Triple(view, view.text, it) } } }
                    var targetX = activity.window.decorView.width / 2f
                    var targetY = if (format == SourceType.TXT) 64f * targetContext.resources.displayMetrics.density else activity.window.decorView.height / 2f
                    var targetView: TextView? = null
                    var targetFound = !textTarget
                    if (textTarget) runOnMainSync {
                        val rect = android.graphics.Rect()
                        val view = snapshots.map { it.first }.firstOrNull {
                            it.text.length > 20 && it.getGlobalVisibleRect(rect) && rect.height() > 30 &&
                                rect.bottom > 170f * targetContext.resources.displayMetrics.density &&
                                rect.top < activity.window.decorView.height * .7f
                        }
                        if (view == null) return@runOnMainSync
                        targetFound = true
                        targetView = view
                        view.getGlobalVisibleRect(rect)
                        targetX = rect.exactCenterX()
                        // Keep the same body coordinate outside the top/bottom bars in BOTH visibility states.
                        targetY = maxOf(rect.top + 20f, 170f * targetContext.resources.displayMetrics.density)
                            .coerceAtMost(rect.bottom - 10f)
                        report("TEXT_TARGET $name bounds=$rect chars=${view.text.length} xy=$targetX,$targetY")
                    }
                    check(targetFound) { "No visible body text for $name" }
                    val frames = mutableListOf<Long>()
                    val callback = object : Choreographer.FrameCallback {
                        var previous = 0L
                        override fun doFrame(time: Long) { if (previous != 0L) frames += (time - previous) / 1_000_000; previous = time; Choreographer.getInstance().postFrameCallback(this) }
                    }
                    runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
                    val durations = mutableListOf<Long>()
                    repeat(6) {
                        val start = SystemClock.elapsedRealtime()
                        val down = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            // Selectable native text consumes taps; use the central reserved area for TXT.
                            val y = targetY
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, targetX, y, 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
                        }
                        val expected = it % 2 == 0
                        val limit = SystemClock.elapsedRealtime() + 2000
                        while (reader.value.controlsVisible != expected && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(5)
                        verify(reader.value.controlsVisible == expected, "$name center tap toggles controls $it")
                        durations += SystemClock.elapsedRealtime() - start
                        SystemClock.sleep(350)
                    }
                    runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
                    runOnMainSync { verify(snapshots.all { (view, text, layout) -> view.isAttachedToWindow && view.text === text && view.layout === layout }, "$name keeps native text and layout while toggling controls") }
                    val sorted = frames.sorted()
                    report("CONTROLS $name tapMs=$durations p95GapMs=${sorted.getOrNull((sorted.size * .95).toInt())} maxGapMs=${sorted.lastOrNull()} over50ms=${sorted.count { it > 50 }}")
                    fun tapAt(x: Float, y: Float) {
                        val start = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).let {
                                it.source = InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(it, true)); it.recycle()
                            }
                        }
                    }
                    if (pageMode == PageMode.SCROLL) {
                        for (fraction in listOf(.12f, .5f, .88f)) repeat(2) { tapIndex ->
                            tapAt(activity.window.decorView.width * fraction, targetY)
                            SystemClock.sleep(350)
                            verify(reader.value.controlsVisible == (tapIndex == 0), "$name scroll tap zone $fraction toggles controls $tapIndex")
                        }
                    }
                    if (textTarget) {
                        fun hasSettingsNode(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                            if (node == null) return false
                            if (node.contentDescription?.toString() == "更多阅读设置") return true
                            return (0 until node.childCount).any { hasSettingsNode(node.getChild(it)) }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                        verify(!hasSettingsNode(uiAutomation.rootInActiveWindow), "$name hidden toolbar has no settings accessibility node")
                        runOnMainSync { reader.value = reader.value.copy(controlsVisible = true) }
                        SystemClock.sleep(300)
                        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                        verify(hasSettingsNode(uiAutomation.rootInActiveWindow), "$name visible toolbar exposes settings accessibility node")
                        runOnMainSync { reader.value = reader.value.copy(controlsVisible = false) }
                        SystemClock.sleep(300)
                        val down = SystemClock.uptimeMillis()
                        // A real long press must select text without opening the controls.
                        var pressX = targetX; var pressY = targetY
                        runOnMainSync { targetView?.let { view ->
                            val loc = IntArray(2); view.getLocationOnScreen(loc)
                            val line = view.layout.getLineForVertical((targetY - loc[1]).toInt())
                            val offset = (view.layout.getLineStart(line) + 4).coerceAtMost(view.layout.getLineEnd(line) - 1)
                            pressX = loc[0] + view.layout.getPrimaryHorizontal(offset) + 2
                            pressY = loc[1] + view.layout.getLineBaseline(line) - view.textSize / 3
                        } }
                        fun inject(action: Int, x: Float, y: Float) {
                            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
                                it.source = InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(it, true)); it.recycle()
                            }
                        }
                        inject(MotionEvent.ACTION_DOWN, pressX, pressY)
                        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
                        inject(MotionEvent.ACTION_UP, pressX, pressY)
                        SystemClock.sleep(250)
                        verify(!reader.value.controlsVisible, "$name long press does not toggle controls")
                        var selected = false
                        runOnMainSync { selected = textViews(activity.window.decorView).any { it.hasSelection() } }
                        verify(selected, "$name native long press still selects text")
                        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        SystemClock.sleep(250)
                        runOnMainSync { targetView?.clearFocus() }
                        waitForIdleSync()
                        val dragDown = SystemClock.uptimeMillis()
                        for (step in 0..10) {
                            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                            val x = if (pageMode == PageMode.PAGED) targetX - step * 25 else targetX
                            val y = if (pageMode == PageMode.SCROLL) targetY - step * 25 else targetY
                            MotionEvent.obtain(dragDown, SystemClock.uptimeMillis(), action, x, y, 0).let {
                                it.source = InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(it, true)); it.recycle()
                            }
                            SystemClock.sleep(20)
                        }
                        SystemClock.sleep(350)
                        verify(!reader.value.controlsVisible, "$name drag does not toggle controls")
                        runOnMainSync { textViews(activity.window.decorView).forEach { it.clearFocus() } }
                        waitForIdleSync()
                        if (pageMode == PageMode.PAGED) {
                            runOnMainSync {
                                textViews(activity.window.decorView).forEach { view ->
                                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                                    report("PAGE_TAP_VIEW xy=${xy.toList()} size=${view.width}x${view.height} selected=${view.hasSelection()} chars=${view.text.length}")
                                    if (view is HighlightedTextView) {
                                        val callback = view.onReaderTap
                                        view.onReaderTap = { x -> report("PAGE_NATIVE_TAP screenX=$x"); callback?.invoke(x) }
                                    }
                                }
                            }
                            val before = pageProgress.get()
                            progressHistory.clear(); progressHistory.add(before)
                            repeat(12) {
                                tapAt(activity.window.decorView.width * .88f, activity.window.decorView.height * .5f)
                                SystemClock.sleep(35)
                            }
                            SystemClock.sleep(750)
                            runOnMainSync { report("PAGE_AFTER_TAPS selections=" + textViews(activity.window.decorView).map { it.hasSelection() }) }
                            val history = synchronized(progressHistory) { progressHistory.toList() }
                            verify(history.zipWithNext().all { (a, b) -> b >= a }, "$name rapid right taps never report a previous page: $history")
                            verify(pageProgress.get() > before, "$name right taps advance at least one page")
                            val afterRight = pageProgress.get()
                            tapAt(activity.window.decorView.width * .12f, activity.window.decorView.height * .5f)
                            SystemClock.sleep(750)
                            verify(pageProgress.get() < afterRight, "$name left tap goes to previous page")
                            verify(!reader.value.controlsVisible, "$name edge page turns do not open controls")
                            runOnMainSync { textViews(activity.window.decorView).forEach { it.clearFocus() } }
                            waitForIdleSync()
                        }
                    }
                    if (!textTarget) {
                    val trace = File(targetContext.getExternalFilesDir(null), "controls-$label-$name.trace")
                    runOnMainSync { android.os.Debug.startMethodTracing(trace.path, 32_000_000) }
                    repeat(4) { runOnMainSync { reader.value = reader.value.copy(controlsVisible = !reader.value.controlsVisible) }; SystemClock.sleep(350) }
                    runOnMainSync { android.os.Debug.stopMethodTracing() }
                    report("TRACE ${trace.path}")
                    }
                }
            }
            result.putString("stream", "PASS reader-controls checks=$checks"); success = true
        } catch (error: Throwable) { result.putString("stream", "FAIL ${error.stackTraceToString()}") }
        finally { if (initialized) runBlocking {
            fixtureIds.forEach { id -> container.database.bookDao().get(id)?.let { container.database.bookDao().delete(it) }; container.storage.bookDirectory(id).deleteRecursively() }
            container.database.searchDao().prune()
        } }
        finish(if (success) -1 else 0, result)
    }
}
