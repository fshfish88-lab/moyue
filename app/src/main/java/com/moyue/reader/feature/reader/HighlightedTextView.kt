package com.moyue.reader.feature.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Spanned
import android.widget.TextView
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

internal class ReaderHighlightSpan(val color: Int)

/** Draw selection geometry within glyph metrics, excluding line spacing and blank separators. */
internal class HighlightedTextView(context: Context) : TextView(context) {
    var onTextLayout: (android.text.Layout) -> Unit = {}
    // Absolute screen X: a paged TextView can be translated while a tap is in flight.
    var onReaderTap: ((Float) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f

    // TextView retains its standard selection/accessibility actions. The separate reading action
    // below also supports accessibility clicks, which have no left/right touch coordinate.
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK && onReaderTap != null && !hasSelection()) {
            val origin = IntArray(2); getLocationOnScreen(origin)
            onReaderTap?.invoke(origin[0] + width / 2f)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                tapCandidate = !hasSelection()
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) tapCandidate = false
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
        val readerTap = event.actionMasked == MotionEvent.ACTION_UP && tapCandidate &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() && !hasSelection()
        // Keep native selection/ActionMode handling. Compose's parent tap detector cannot see a
        // completed tap after a selectable AndroidView consumes it, so forward only short taps.
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            tapCandidate = false
            if (handled && readerTap && !hasSelection()) {
                // A selectable TextView takes focus on tap. Release it before navigation so
                // Compose does not bring the old focused page back into view after the turn.
                clearFocus()
                onReaderTap?.invoke(downX)
            }
        }
        return handled
    }

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
