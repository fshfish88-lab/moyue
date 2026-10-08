package com.moyue.reader.feature.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Spanned
import android.widget.TextView

internal class ReaderHighlightSpan(val color: Int)

/** Draw selection geometry within glyph metrics, excluding line spacing and blank separators. */
internal class HighlightedTextView(context: Context) : TextView(context) {
    var onTextLayout: (android.text.Layout) -> Unit = {}
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPath = Path()
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layout?.let(onTextLayout)
    }
    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val styled = text as? Spanned
        if (layout != null && styled != null) {
            val save = canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            val metrics = paint.fontMetrics
            for (mark in styled.getSpans(0, styled.length, ReaderHighlightSpan::class.java)) {
                val start = styled.getSpanStart(mark); val end = styled.getSpanEnd(mark)
                if (start < 0 || end <= start) continue
                highlightPaint.color = mark.color
                for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
                    var from = maxOf(start, layout.getLineStart(line))
                    var to = minOf(end, layout.getLineEnd(line))
                    while (from < to && styled[from].isWhitespace()) from++
                    while (to > from && styled[to - 1].isWhitespace()) to--
                    if (from >= to) continue
                    highlightPath.reset(); layout.getSelectionPath(from, to, highlightPath)
                    val clipped = canvas.save()
                    val baseline = layout.getLineBaseline(line)
                    canvas.clipRect(0f, baseline + metrics.ascent, width.toFloat(), baseline + metrics.descent)
                    canvas.drawPath(highlightPath, highlightPaint)
                    canvas.restoreToCount(clipped)
                }
            }
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }
}
