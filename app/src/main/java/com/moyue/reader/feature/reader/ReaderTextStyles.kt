package com.moyue.reader.feature.reader

import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.style.StyleSpan
import com.moyue.reader.core.model.ContentBlock

/** Keep semantic styling identical in StaticLayout and TextView without changing source offsets. */
internal fun Spannable.styleBlock(block: ContentBlock, start: Int, end: Int) {
    if (start >= end || start < 0 || end > length) return
    val style = when (block) {
        is ContentBlock.Heading -> Typeface.BOLD
        is ContentBlock.Quote -> Typeface.ITALIC
        else -> return
    }
    setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
