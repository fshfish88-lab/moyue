package com.moyue.reader.feature.reader

import org.junit.Assert.*
import org.junit.Test

class ChapterTextIndexTest {
    @Test fun respectsExclusiveEndAndSeparators() {
        val index = ChapterTextIndex(listOf(0, 12, 30))
        assertEquals(0..0, index.intersecting(0, 12))
        assertEquals(1..1, index.intersecting(12, 30))
        assertEquals(0..1, index.intersecting(10, 13))
        assertEquals(2..2, index.intersecting(35, 40))
        assertTrue(index.intersecting(2, 2).isEmpty())
        assertTrue(ChapterTextIndex(emptyList()).intersecting(0, 1).isEmpty())
    }
    @Test fun longChapterPageVisitsOnlyIntersectingBlocks() {
        val index = ChapterTextIndex(List(100_000) { it * 100 })
        assertEquals(listOf(80_000, 80_001), index.intersecting(8_000_030, 8_000_180).toList())
    }
}
