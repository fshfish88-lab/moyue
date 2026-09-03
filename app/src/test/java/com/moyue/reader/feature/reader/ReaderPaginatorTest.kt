package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginatorTest {
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
