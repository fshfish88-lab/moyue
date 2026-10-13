package com.moyue.reader.feature.reader

/** Finds only blocks intersecting a page, including a separator at the boundary. */
internal class ChapterTextIndex(private val starts: List<Int>) {
    fun blockAt(offset: Int): Int = starts.binarySearch(offset).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
    fun intersecting(start: Int, end: Int): IntRange =
        if (starts.isEmpty() || end <= start) IntRange.EMPTY else blockAt(start)..blockAt(end - 1)
}
