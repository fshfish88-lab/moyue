package com.moyue.reader.feature.annotations

import com.moyue.reader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TextAnchorsTest {
    @Test fun mapsSyntheticIndentAndParagraphGapToSource() {
        val segments = listOf(TextSegment(2, 6, 3, 2), TextSegment(10, 13, 4, 0))
        assertEquals(ReaderPosition(3, 2), mappedPosition(segments, 0, ReaderPosition(0, 0)))
        assertEquals(ReaderPosition(3, 4), mappedPosition(segments, 4, ReaderPosition(0, 0)))
        assertEquals(ReaderPosition(4, 0), mappedPosition(segments, 8, ReaderPosition(0, 0)))
        assertEquals(ReaderPosition(4, 3), mappedPosition(segments, 30, ReaderPosition(0, 0)))
    }
    @Test fun preservesCrossParagraphQuoteAndUtf16Offsets() {
        val blocks = listOf(ContentBlock.Text("  第一段😀文字"), ContentBlock.Text("第二段结尾"))
        assertEquals("😀文字\n第二段", sourceSelection(blocks, ReaderPosition(0, 5), ReaderPosition(1, 3)))
    }
    @Test fun chineseSubstringTermsAreIndexedWithoutWhitespace() {
        val indexed = SearchTerms.index("海上明月照天涯") .split(' ').toSet()
        val query = SearchTerms.query("明月照").split(' ')
        assertTrue(query.all { it in indexed })
        assertTrue(SearchTerms.query("月").isNotEmpty())
        assertEquals(SearchTerms.query("AbC"), SearchTerms.query("abc"))
    }
    @Test fun punctuationAndFtsOperatorsCannotChangeQuerySyntax() {
        val q = SearchTerms.query("\"* OR 月😀")
        assertTrue(q.split(' ').all { it.matches(Regex("u[0-9a-f]+(x[0-9a-f]+)?")) })
        assertTrue(SearchTerms.index("😀月").contains("u1f600x6708"))
    }
    @Test fun hashChangesWithContentButNotDisplayPreferences() {
        assertEquals(textHash("原文"), textHash("原文"))
        assertNotEquals(textHash("原文"), textHash("原文改"))
    }
}
