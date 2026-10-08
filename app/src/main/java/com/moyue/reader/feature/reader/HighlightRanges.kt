package com.moyue.reader.feature.reader

internal data class HighlightRange(val start: Int, val end: Int, val color: Int)

/** Later marks replace the covered color, leaving the other parts of an older mark intact. */
internal fun overlayHighlight(ranges: List<HighlightRange>, mark: HighlightRange): List<HighlightRange> = buildList {
    for (old in ranges) {
        if (old.end <= mark.start || old.start >= mark.end) add(old)
        else {
            if (old.start < mark.start) add(old.copy(end = mark.start))
            if (old.end > mark.end) add(old.copy(start = mark.end))
        }
    }
    if (mark.start < mark.end) add(mark)
}
