package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import com.moyue.reader.MainActivity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.settings.ReaderPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Same isolated settings UI / chapter workload for before and after measurements. */
class ReaderExperienceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(n: AccessibilityNodeInfo) { add(n); for(i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 10000
        while(SystemClock.uptimeMillis() < until) {
            nodes().firstOrNull { it.text?.toString() == text }?.let { return it }
            SystemClock.sleep(15)
        }
        error("Missing $text")
    }
    override fun onStart() {
        val out = Bundle(); val result = JSONObject()
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val visible = mutableStateOf(false)
            val prefs = mutableStateOf(ReaderPreferences())
            var changes = 0
            runOnMainSync { activity.setContent { MaterialTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    TextButton(onClick = { visible.value = true }) { Text("打开测试设置") }
                }
                if(visible.value) ReaderSettingsSheet(prefs.value, { changes++; prefs.value = it }, { visible.value = false })
            } } }
            waitText("打开测试设置")
            val opens = mutableListOf<Long>(); val frames = mutableListOf<Long>()
            var previous = 0L
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) { if(previous != 0L) frames += (time-previous)/1_000_000; previous=time; Choreographer.getInstance().postFrameCallback(this) }
            }
            runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
            repeat(4) {
                val start=SystemClock.uptimeMillis()
                runOnMainSync { visible.value=true }
                waitText("字体")
                uiAutomation.waitForIdle(80,5000)
                opens += SystemClock.uptimeMillis()-start
                runOnMainSync { visible.value=false }
                // API 26 can retain the dismissed dialog's accessibility window in its cache.
                // The underlying activity is already visible (also checked by screenshot).
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    waitForIdleSync(); SystemClock.sleep(200)
                    runOnMainSync { check(activity.hasWindowFocus()) { "Settings dialog still owns focus" } }
                } else {
                    waitText("打开测试设置")
                }
                SystemClock.sleep(200)
            }
            runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
            result.put("settingsOpenMs", opens).put("frameMaxMs", frames.maxOrNull()).put("frameP95Ms", frames.sorted().let { it[(it.size*.95).toInt().coerceAtMost(it.lastIndex)] })
            val blocks = List(3000) { ContentBlock.Text("第${it}段。" + "长章节分页保持位置和文本完整。".repeat(4)) }
            val timings=mutableListOf<Long>()
            runBlocking { repeat(3) {
                val start=SystemClock.elapsedRealtime()
                val pages=NativePageLayout.create(blocks,ReaderPreferences(),900,1500,48f)
                timings += SystemClock.elapsedRealtime()-start
                result.put("pages",pages.size).put("segments",pages.sumOf { it.segments.size })
            } }
            result.put("paginationMs",timings)
            runBlocking {
                val face = ReaderFonts.load(targetContext, com.moyue.reader.core.settings.ReaderFont.LXGW)
                check(face != android.graphics.Typeface.SERIF)
                check(face === ReaderFonts.load(targetContext, com.moyue.reader.core.settings.ReaderFont.LXGW))
                val cache = ReaderPageCache()
                val first = cache.pages(blocks, ReaderPreferences(), 900, 1500, 48f, face)
                val hitStart = SystemClock.elapsedRealtimeNanos()
                check(first === cache.pages(blocks, ReaderPreferences().copy(keepScreenOn = true), 900, 1500, 48f, face))
                result.put("cacheHitMicros", (SystemClock.elapsedRealtimeNanos() - hitStart) / 1000)
                check(first !== cache.pages(blocks, ReaderPreferences(), 800, 1500, 48f, face))
                check(first !== cache.pages(blocks.toList(), ReaderPreferences(), 900, 1500, 48f, face))
                val system = cache.pages(blocks, ReaderPreferences(), 900, 1500, 48f, android.graphics.Typeface.DEFAULT)
                check(first !== system)
                val covered = IntArray(blocks.size)
                for (page in first) for (segment in page.segments) {
                    val original = (blocks[segment.blockIndex] as ContentBlock.Text).text
                    check(page.text.substring(segment.displayStart, segment.displayEnd) == original.substring(segment.sourceStart, segment.sourceStart + segment.displayEnd - segment.displayStart))
                    covered[segment.blockIndex] += segment.displayEnd - segment.displayStart
                }
                check(covered.indices.all { covered[it] == (blocks[it] as ContentBlock.Text).text.length })
                result.put("fontCacheAndTextCoverage", "PASS")
            }
            runOnMainSync { visible.value = true }
            waitText("字体"); uiAutomation.waitForIdle(80,5000)
            // Touch coordinates must be sampled after the sheet's opening animation settles.
            SystemClock.sleep(500)
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val slider = nodes().first { it.rangeInfo != null }
            val bounds = android.graphics.Rect().also(slider::getBoundsInScreen)
            result.put("sliderBounds", bounds.toShortString())
            val before = changes
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, fraction: Float) {
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.left + bounds.width() * fraction, bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, .35f)
            for(i in 4..8) { touch(android.view.MotionEvent.ACTION_MOVE, i / 10f); SystemClock.sleep(40) }
            check(changes == before) { "Slider persisted during drag" }
            touch(android.view.MotionEvent.ACTION_UP, .8f)
            waitForIdleSync(); SystemClock.sleep(100)
            check(changes == before + 1) { "Slider must commit exactly once: ${changes-before}" }
            check(prefs.value.fontSizeSp >= 26f)
            result.put("sliderCommit", "PASS")
            runOnMainSync { visible.value = false }
            out.putString("stream","PASS reader experience measurement")
        } catch(e: Throwable) { out.putString("stream","FAIL ${e.stackTraceToString()}") }
        finally { out.putString("metrics",result.toString()); finish(if(out.getString("stream").orEmpty().startsWith("PASS")) -1 else 0,out) }
    }
}

