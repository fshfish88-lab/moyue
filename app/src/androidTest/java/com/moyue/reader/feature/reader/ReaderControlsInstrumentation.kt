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

/** Uses existing source files read-only; does not write library, preferences or progress. */
class ReaderControlsInstrumentation : Instrumentation() {
    private var label = "controls"
    private var formatOnly: String? = null
    private var checks = 0
    private fun report(value: String) = sendStatus(1, Bundle().apply { putString("stream", value) })
    private fun verify(ok: Boolean, value: String) { check(ok) { value }; checks++; report("PASS $checks: $value") }
    override fun onCreate(arguments: Bundle?) { label = arguments?.getString("label") ?: label; formatOnly = arguments?.getString("format"); super.onCreate(arguments); start() }
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
                val indexes = if (format == SourceType.EPUB) listOf(0, minOf(3, chapters.lastIndex)) else listOf(0)
                for (index in indexes) {
                    val chapter = runBlocking { RoomReaderDataSource(container.database, container.storage).load(book, chapters[index]) }
                    val annotations = runBlocking { container.database.annotationDao().forBook(book.id).first() }
                    val reader = mutableStateOf(ReaderState(book.id, book.title, chapter, chapters, ReaderPosition(0, 0), 0f, 0f, false, prefs.copy(pageMode = PageMode.SCROLL), false, false))
                    runOnMainSync { activity.setContent { MaterialTheme {
                        val current = reader.value
                        ReaderScreen(current, onBack = {}, onToggleControls = { reader.value = reader.value.copy(controlsVisible = !reader.value.controlsVisible) },
                            onPrevious = {}, onNext = {}, onChapter = {}, onPosition = { _, _ -> }, onPreferences = { reader.value = reader.value.copy(preferences = it) },
                            onScrollPosition = { _, _, _ -> }, onPrefetch = {}, annotations = annotations + emptyList(),
                            onSelection = { _, _, _, _ -> check(current.bookId == book.id) })
                    } } }
                    SystemClock.sleep(1600)
                    val name = "${format.name}-$index"
                    val snapshots = mutableListOf<Triple<TextView, CharSequence, android.text.Layout>>()
                    runOnMainSync { textViews(activity.window.decorView).forEach { view -> view.layout?.let { snapshots += Triple(view, view.text, it) } } }
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
                            val y = if (format == SourceType.TXT) 64f * targetContext.resources.displayMetrics.density else activity.window.decorView.height / 2f
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, activity.window.decorView.width / 2f, y, 0)
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
                    val trace = File(targetContext.getExternalFilesDir(null), "controls-$label-$name.trace")
                    runOnMainSync { android.os.Debug.startMethodTracing(trace.path, 32_000_000) }
                    repeat(4) { runOnMainSync { reader.value = reader.value.copy(controlsVisible = !reader.value.controlsVisible) }; SystemClock.sleep(350) }
                    runOnMainSync { android.os.Debug.stopMethodTracing() }
                    report("TRACE ${trace.path}")
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
