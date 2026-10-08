package com.moyue.reader.feature.reader

import org.junit.Assert.*
import org.junit.Test

class HighlightRangesTest {
    @Test fun recoloringReplacesThePreviousColor() {
        assertEquals(listOf(HighlightRange(2, 8, 2)), overlayHighlight(listOf(HighlightRange(2, 8, 1)), HighlightRange(2, 8, 2)))
    }
    @Test fun overlappingSelectionPreservesTheUncoveredParts() {
        assertEquals(listOf(HighlightRange(0, 3, 1), HighlightRange(7, 10, 1), HighlightRange(3, 7, 2)),
            overlayHighlight(listOf(HighlightRange(0, 10, 1)), HighlightRange(3, 7, 2)))
    }
    @Test fun replacementCanCoverSeveralOlderSelectionsWithoutBlending() {
        val result = overlayHighlight(listOf(HighlightRange(0, 4, 1), HighlightRange(4, 8, 2), HighlightRange(12, 16, 3)), HighlightRange(2, 14, 4))
        for (i in 0 until 16) assertEquals(1, result.count { i in it.start until it.end })
        assertEquals(4, result.single { 8 in it.start until it.end }.color)
        assertEquals(1, result.single { 1 in it.start until it.end }.color)
        assertEquals(3, result.single { 15 in it.start until it.end }.color)
    }
    @Test fun adjacentSelectionsRemainSeparate() {
        assertEquals(listOf(HighlightRange(0, 4, 1), HighlightRange(4, 8, 2)), overlayHighlight(listOf(HighlightRange(0, 4, 1)), HighlightRange(4, 8, 2)))
    }
}
