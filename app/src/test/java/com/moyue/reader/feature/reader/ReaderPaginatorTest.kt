package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginatorTest {
    @Test fun measuredPagesFitAndPreserveIndentedText() {
        val blocks = listOf(ContentBlock.Text("天地玄黄".repeat(30)), ContentBlock.Text("宇宙洪荒".repeat(20)))
        val pages = ReaderPaginator().paginateMeasured(blocks, true) { it.length <= 23 }
        assertTrue(pages.all { it.text.length <= 23 })
        assertEquals("　　" + "天地玄黄".repeat(30) + "\n\n　　" + "宇宙洪荒".repeat(20), pages.joinToString("") { it.text })
        assertEquals(0, pages.first().start.charOffset)
    }
    @Test fun surrogatePairsStayTogether() {
        val pages = ReaderPaginator().paginateMeasured(listOf(ContentBlock.Text("天地😀玄黄😀宇宙")), false) { it.length <= 3 }
        assertEquals("天地😀玄黄😀宇宙", pages.joinToString("") { it.text })
        assertTrue(pages.none { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
    }

    @Test
    fun paginationDoesNotLoseOrDuplicateCharacters() {
        val blocks = listOf(
            ContentBlock.Text("山雨欲来风满楼"),
            ContentBlock.Text("夜色渐深，灯火依旧。"),
        )

        val pages = ReaderPaginator().paginate(blocks, maxCharactersPerPage = 7)

        assertTrue(pages.size > 1)
        assertEquals("山雨欲来风满楼\n夜色渐深，灯火依旧。", pages.joinToString("") { it.text })
        assertEquals(0, pages.first().start.blockIndex)
        assertEquals(1, pages.last().end.blockIndex)
    }
}
