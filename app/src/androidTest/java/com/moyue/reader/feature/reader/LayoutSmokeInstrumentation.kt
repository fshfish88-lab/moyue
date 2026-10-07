package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.settings.ReaderPreferences
import kotlinx.coroutines.runBlocking

@android.annotation.SuppressLint("InlinedApi")
open class LayoutSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val started = System.currentTimeMillis()
            var checked = 0
            runBlocking {
                val illustration = ContentBlock.Image("/test/illustration.png", "插图")
                val mixed = NativePageLayout.create(listOf(ContentBlock.Text("前文"), illustration, ContentBlock.Text("后文")), ReaderPreferences(), 900, 1500, 48f)
                check(mixed.size == 3 && mixed[1].image == illustration)
                check(mixed.map { it.start.blockIndex } == listOf(0, 1, 2))
                check(mixed.first().text.contains("前文") && mixed.last().text.contains("后文"))
                for (font in listOf(28f, 48f, 84f)) {
                    val prefs = ReaderPreferences()
                    val paragraph = "中文段落不会在页面底部截断。English words and 😀表情也应完整显示。".repeat(120)
                    val blocks = listOf(ContentBlock.Text(paragraph), ContentBlock.Text(paragraph))
                    val pages = NativePageLayout.create(blocks, prefs, 900, 1500, font)
                    check(pages.joinToString("") { it.text } == "　　" + paragraph + "\n\n　　" + paragraph)
                    for (page in pages) {
                        var bottom = 0
                        runOnMainSync {
                            val view = TextView(targetContext).apply {
                                includeFontPadding = false
                        setElegantTextHeight(false)
                        if (android.os.Build.VERSION.SDK_INT >= 28) setFallbackLineSpacing(true)
                        if (android.os.Build.VERSION.SDK_INT >= 35) setLocalePreferredLineHeightForMinimumUsed(false); setPadding(0, 0, 0, 0)
                                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, font)
                                typeface = prefs.nativeTypeface
                                setLineSpacing(font * (prefs.nativeLineFactor - 1f), 1f)
                                text = page.text.trimEnd('\n')
                            }
                            view.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
                            view.layout(0, 0, 900, 1500)
                            bottom = view.layout.getLineBottom(view.layout.lineCount - 1)
                        }
                        check(bottom <= 1500) { "clipped font=$font bottom=$bottom" }
                        checked++
                    }
                }
            }
            result.putString("stream", "PASS pages=$checked elapsedMs=${System.currentTimeMillis() - started}")
            finish(-1, result)
        } catch(error: Throwable) {
            result.putString("stream", "FAIL ${error.stackTraceToString()}"); finish(0, result)
        }
    }
}
