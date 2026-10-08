package com.moyue.reader.feature.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.core.view.doOnLayout
import com.moyue.reader.core.database.AnnotationEntity
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.feature.annotations.*
import org.json.JSONObject

@Composable
@android.annotation.SuppressLint("InlinedApi")
internal fun AnnotatedReaderText(text: String, chapter: ReaderChapter, segments: List<TextSegment>, preferences: ReaderPreferences,
    annotations: List<AnnotationEntity>, modifier: Modifier, indent: Boolean = false,
    onLayout: (android.text.Layout) -> Unit = {}, onSelecting: (Boolean) -> Unit = {},
    onSelection: (ReaderChapter, ReaderPosition, ReaderPosition, String) -> Unit) {
    val density = LocalDensity.current
    val fontPx = with(density) { preferences.fontSizeSp.sp.toPx() }
    val ink = MaterialTheme.colorScheme.onBackground.toArgb()
    val selectedCallback by rememberUpdatedState(onSelection)
    val selectingCallback by rememberUpdatedState(onSelecting)
    val layoutCallback by rememberUpdatedState(onLayout)
    val positionSegments by rememberUpdatedState(segments)
    val currentChapter by rememberUpdatedState(chapter)
    val styled = remember(text, chapter, segments, annotations, indent, fontPx) {
        SpannableString(text).apply {
            if (indent && isNotEmpty()) setSpan(LeadingMarginSpan.Standard((fontPx * 2).toInt(), 0), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            annotations.filter { it.type != "BOOKMARK" }.forEach { a ->
                val anchor = runCatching { JSONObject(a.anchorJson) }.getOrNull() ?: return@forEach
                if (anchor.optString("kind") != "TEXT" || anchor.optLong("chapterId") != chapter.id) return@forEach
                val first = anchor.optInt("blockIndex"); val last = anchor.optInt("endBlock", first)
                if (first !in chapter.blocks.indices || a.sourceHash != textHash(blockText(chapter.blocks[first]))) return@forEach
                segments.forEach { segment ->
                    if (segment.blockIndex in first..last) {
                        val from = if (segment.blockIndex == first) anchor.optInt("charOffset") else 0
                        val to = if (segment.blockIndex == last) anchor.optInt("endOffset") else Int.MAX_VALUE
                        val left = segment.displayStart + (from - segment.sourceStart).coerceIn(0, segment.displayEnd - segment.displayStart)
                        val right = segment.displayStart + (to - segment.sourceStart).coerceIn(0, segment.displayEnd - segment.displayStart)
                        if (left < right && right <= length) setSpan(BackgroundColorSpan(annotationColor(a.color)), left, right, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
    }
    AndroidView(modifier = modifier, factory = { context -> TextView(context).apply {
        includeFontPadding = false; setElegantTextHeight(false); setPadding(0, 0, 0, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) setFallbackLineSpacing(true)
        if (android.os.Build.VERSION.SDK_INT >= 35) setLocalePreferredLineHeightForMinimumUsed(false)
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setTextIsSelectable(true)
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.add(0, 16001, 0, "高亮"); menu?.add(0, 16002, 1, "笔记")
                selectingCallback(true); return true
            }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                if (item?.itemId !in setOf(16001, 16002)) return false
                val left = minOf(selectionStart, selectionEnd); val right = maxOf(selectionStart, selectionEnd)
                if (left >= 0 && right > left && positionSegments.any { it.displayStart < right && it.displayEnd > left }) {
                    val start = mappedPosition(positionSegments, left, ReaderPosition(0, 0))
                    // End-exclusive position must refer to the last selected source character, not a following separator.
                    val tail = mappedPosition(positionSegments, right - 1, start)
                    val end = tail.copy(charOffset = tail.charOffset + 1)
                    selectedCallback(currentChapter, start, end, if (item?.itemId == 16002) "NOTE" else "HIGHLIGHT")
                }
                mode?.finish(); return true
            }
            override fun onDestroyActionMode(mode: ActionMode?) { selectingCallback(false) }
        }
    } }, update = { view ->
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPx)
        view.typeface = preferences.nativeTypeface
        view.setLineSpacing(fontPx * (preferences.nativeLineFactor - 1), 1f); view.setTextColor(ink)
        if (view.tag !== styled) { view.text = styled; view.tag = styled }
        view.doOnLayout { view.layout?.let(layoutCallback) }
    })
}
