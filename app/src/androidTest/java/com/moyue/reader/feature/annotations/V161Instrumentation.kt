package com.moyue.reader.feature.annotations

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import com.moyue.reader.*
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.core.settings.SpacingLevel
import com.moyue.reader.feature.importbook.ImportState
import com.moyue.reader.feature.reader.RoomReaderDataSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Real reader regression checks. Only this runner's imported fixture is removed. */
class V161Instrumentation : Instrumentation() {
    private val failures = mutableListOf<String>()
    private var count = 0
    private var suite = "reader"
    private fun report(text: String) = sendStatus(1, Bundle().apply { putString("stream", text) })
    private fun verify(ok: Boolean, label: String) {
        count++; report("${if (ok) "PASS" else "FAIL"} $count: $label")
        if (!ok) failures += label
    }
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite") ?: "reader"; super.onCreate(arguments); start() }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(n: AccessibilityNodeInfo) { add(n); for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        uiAutomation.rootInActiveWindow?.let(::walk)
        uiAutomation.windows.mapNotNull { it.root }.forEach(::walk)
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(120) {
            nodes().lastOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(35)
        }
    }
    private fun click(label: String) {
        val rect = android.graphics.Rect(); waitFor(label).getBoundsInScreen(rect)
        report("tap $label bounds=$rect")
        tap(rect.exactCenterX(), rect.exactCenterY())
    }
    private fun findText(view: View, needle: String): TextView? {
        if (view is TextView && view.text.contains(needle)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), needle)?.let { return it }
        return null
    }
    override fun onStart() {
        val out = Bundle()
        lateinit var c: MoyueContainer
        runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
        val preferences = runBlocking { c.preferences.preferences.first() }
        var fixtureId: Long? = null
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val input = targetContext.cacheDir.resolve("V161回归.txt").apply {
                writeText("第一章 回归\n\n" + "明月照天涯，星光入梦。远处的灯光一点一点亮起。".repeat(12) + "\n\n第二段结束。")
            }
            val id = runBlocking {
                (c.importService.importDocument(android.net.Uri.fromFile(input), "V161回归.txt") as ImportState.Completed).bookId.also { value ->
                    c.database.bookDao().get(value)!!.let { c.database.bookDao().update(it.copy(title = "V161回归")) }
                }
            }
            fixtureId = id
            val chapter = runBlocking {
                val book = c.database.bookDao().get(id)!!
                RoomReaderDataSource(c.database, c.storage).load(book, c.database.chapterDao().forBook(id).first())
            }
            val block = chapter.blocks.indexOfFirst { blockText(it).startsWith("明月照天涯") }
            val text = blockText(chapter.blocks[block])
            val draft = textDraft(id, chapter, ReaderPosition(block, 0), ReaderPosition(block, text.length))
            val firstId = runBlocking { c.annotations.save(draft, "保留已有笔记", "green") }
            SystemClock.sleep(25)
            val replacementId = runBlocking { c.annotations.save(draft, "", "blue") }
            val items = runBlocking { c.database.annotationDao().forBook(id).first() }
            verify(replacementId == firstId && items.size == 1, "reselecting the same quote updates its color without duplicate records")
            verify(items.firstOrNull { it.id == firstId }?.let { it.note == "保留已有笔记" && it.color == "blue" } == true, "recolor preserves existing note")
            // Leave both records in the pre-fix run, so the actual overlapping paint is exercised.
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val readerPreferences = mutableStateOf(preferences.copy(pageMode = PageMode.SCROLL, lineSpacing = SpacingLevel.WIDE))
            val chapters = runBlocking { c.database.chapterDao().forBook(id) }
            // Render the real reader with visible controls to isolate settings composition latency.
            runOnMainSync { activity.setContent { MaterialTheme {
                com.moyue.reader.feature.reader.ReaderScreen(
                    com.moyue.reader.feature.reader.ReaderState(id, "V161回归", chapter, chapters, ReaderPosition(0, 0), 0f, 0f, true, readerPreferences.value, false, false),
                    onBack = {}, onToggleControls = {}, onPrevious = {}, onNext = {}, onChapter = {}, onPosition = { _, _ -> },
                    onPreferences = { readerPreferences.value = it }, onScrollPosition = { _, _, _ -> }, onPrefetch = {}, annotations = items)
            } } }
            SystemClock.sleep(1000)
            var emptyGap = true
            var sampled = 0
            var bluePixels = 0; var greenPixels = 0
            runOnMainSync {
                val view = checkNotNull(findText(activity.window.decorView, "明月照天涯"))
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) in 95..110) {
                        val red = android.graphics.Color.red(pixel); val green = android.graphics.Color.green(pixel); val blue = android.graphics.Color.blue(pixel)
                        if (blue - green > 20 && green - red > 20) bluePixels++
                        if (green - red > 30 && green - blue > 30) greenPixels++
                    }
                }
                val layout = view.layout; val fm = view.paint.fontMetrics
                for (line in 0 until minOf(4, layout.lineCount - 1)) {
                    val gapTop = (layout.getLineBaseline(line) + fm.descent + 2).toInt()
                    val gapBottom = (layout.getLineBaseline(line + 1) + fm.ascent - 2).toInt()
                    if (gapBottom > gapTop) {
                        val x = (layout.getPrimaryHorizontal(layout.getLineStart(line) + 2)).toInt().coerceIn(0, bitmap.width - 1)
                        val y = (gapTop + gapBottom) / 2
                        if (y in 0 until bitmap.height) { sampled++; if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) != 0) emptyGap = false }
                    }
                }
                val file = File(targetContext.getExternalFilesDir(null), "v161-text-$suite.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            verify(sampled > 0 && emptyGap, "saved highlights leave the interline whitespace unpainted (samples=$sampled)")
            verify(bluePixels > 100 && greenPixels == 0, "actual reader pixels show only the latest blue color (blue=$bluePixels green=$greenPixels)")
            val beforeFrames = mutableListOf<Long>()
            var watching = true
            val callback = object : Choreographer.FrameCallback {
                var previous = 0L
                override fun doFrame(time: Long) {
                    if (previous > 0) synchronized(beforeFrames) { beforeFrames += (time - previous) / 1000000 }
                    previous = time
                    if (watching) Choreographer.getInstance().postFrameCallback(this)
                }
            }
            runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
            if (suite == "profile") android.os.Debug.startMethodTracingSampling(File(targetContext.getExternalFilesDir(null), "v161-settings.trace").path, 16000000, 1000)
            val durations = mutableListOf<Long>()
            repeat(4) {
                // The fixture opens with the reader controls visible.
                val settingsBounds = android.graphics.Rect(); waitFor("更多阅读设置").getBoundsInScreen(settingsBounds)
                val start = SystemClock.elapsedRealtime(); tap(settingsBounds.exactCenterX(), settingsBounds.exactCenterY())
                val injected = SystemClock.elapsedRealtime(); waitFor("主题")
                report("SETTINGS inputMs=${injected - start} visibleWaitMs=${SystemClock.elapsedRealtime() - injected}")
                durations += SystemClock.elapsedRealtime() - start
                SystemClock.sleep(650); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitFor("阅读设置"); SystemClock.sleep(250)
            }
            runOnMainSync { watching = false; Choreographer.getInstance().removeFrameCallback(callback) }
            if (suite == "profile") android.os.Debug.stopMethodTracing()
            val frames = synchronized(beforeFrames) { beforeFrames.sorted() }
            report("SETTINGS openMs=$durations frames=${frames.size} p95GapMs=${frames.getOrNull((frames.size * .95).toInt())} maxGapMs=${frames.lastOrNull()} over50ms=${frames.count { it > 50 }}")
            verify(durations.all { it < 1500 }, "reading settings opens within 1500ms on the test emulator")
            // Verify that all settings remain reachable without changing the existing layout.
            click("更多阅读设置"); val settingsWindow = waitFor("主题").windowId
            var reachedBottom = false
            repeat(12) {
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                if (nodes().any { it.windowId == settingsWindow && it.text?.toString() == "恢复默认" && it.isVisibleToUser }) {
                    reachedBottom = true
                } else {
                    nodes().lastOrNull { it.windowId == settingsWindow && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(200)
                }
            }
            verify(reachedBottom, "offscreen settings remain reachable by scrolling")
            click("恢复默认"); SystemClock.sleep(250)
            verify(readerPreferences.value == com.moyue.reader.core.settings.ReaderPreferences(), "bottom reset action updates reader preferences")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.getExternalFilesDir(null), "v161-reader.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        } catch (e: Throwable) { failures += e.stackTraceToString() }
        finally {
            runBlocking {
                c.preferences.update { preferences }
                fixtureId?.let { id -> c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }; c.storage.bookDirectory(id).deleteRecursively() }
                c.database.searchDao().prune()
            }
            targetContext.cacheDir.resolve("V161回归.txt").delete()
        }
        out.putString("stream", "${if (failures.isEmpty()) "PASS" else "FAIL"} v161 checks=$count\n${failures.joinToString("\n")}")
        finish(if (failures.isEmpty()) -1 else 0, out)
    }
}
