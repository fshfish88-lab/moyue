package com.moyue.reader.feature.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderFont
import com.moyue.reader.core.settings.SpacingLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal val ReaderPreferences.nativeTypeface: Typeface get() = when (fontFamily) {
    ReaderFont.SERIF, ReaderFont.LXGW -> Typeface.SERIF
    else -> Typeface.DEFAULT
}
internal val ReaderPreferences.nativeLineFactor: Float get() = when (lineSpacing) {
    SpacingLevel.COMPACT -> 1.45f; SpacingLevel.STANDARD -> 1.7f; SpacingLevel.WIDE -> 2f
}

/** One native layout pass, off the UI thread. Rendering uses the same paint and spacing. */
// LineBreaker constants are inlined integers supported by StaticLayout since API 23.
@android.annotation.SuppressLint("InlinedApi")
internal object NativePageLayout {
    suspend fun create(blocks: List<ContentBlock>, preferences: ReaderPreferences, width: Int, height: Int, fontPx: Float): List<ReaderPage> {
        require(width > 0 && height >= fontPx * 1.5f) { "阅读区域过小，请减小字号或旋转屏幕" }
        if (blocks.any { it is ContentBlock.Image }) return buildList {
            var start = 0
            suspend fun flush(end: Int) {
                if (end > start) addAll(create(blocks.subList(start, end), preferences, width, height, fontPx).map {
                    it.copy(start = it.start.copy(blockIndex = it.start.blockIndex + start), end = it.end.copy(blockIndex = it.end.blockIndex + start))
                })
            }
            blocks.forEachIndexed { index, block ->
                if (block is ContentBlock.Image) {
                    flush(index)
                    add(ReaderPage("", ReaderPosition(index, 0), ReaderPosition(index, 0), block))
                    start = index + 1
                }
            }
            flush(blocks.size)
        }
        val originals = blocks.map { when(it) {
            is ContentBlock.Text -> it.text; is ContentBlock.Heading -> it.text; is ContentBlock.Quote -> it.text
            is ContentBlock.Image -> it.description.orEmpty(); ContentBlock.Divider -> "· · ·"
        } }
        val prefixes = blocks.map { if (preferences.indentParagraphs && it is ContentBlock.Text) "　　" else "" }
        val texts = originals.mapIndexed { i, text -> prefixes[i] + text.trimStart() }
        val separator = when(preferences.paragraphSpacing) { SpacingLevel.COMPACT -> "\n"; SpacingLevel.STANDARD -> "\n\n"; SpacingLevel.WIDE -> "\n\n\n" }
        var length = 0
        val starts = texts.map { text -> length.also { length += text.length + separator.length } }
        val full = texts.joinToString(separator)
        if (full.isEmpty()) return listOf(ReaderPage("", ReaderPosition(0, 0), ReaderPosition(0, 0)))
        fun position(offset: Int): ReaderPosition {
            val i = starts.binarySearch(offset).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
            val leading = originals[i].length - originals[i].trimStart().length
            return ReaderPosition(i, (offset - starts[i] - prefixes[i].length + leading).coerceIn(0, originals[i].length))
        }
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = fontPx; typeface = preferences.nativeTypeface }
        val layout = StaticLayout.Builder.obtain(full, 0, full.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(fontPx * (preferences.nativeLineFactor - 1f), 1f)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }.build()
        return buildList {
            var line = 0
            while (line < layout.lineCount) {
                currentCoroutineContext().ensureActive()
                val first = line
                val top = layout.getLineTop(first)
                while (line + 1 < layout.lineCount && layout.getLineBottom(line + 1) - top <= height) line++
                val start = layout.getLineStart(first)
                val end = layout.getLineEnd(line)
                add(ReaderPage(full.substring(start, end), position(start), position(end)))
                line++
            }
        }
    }
}
