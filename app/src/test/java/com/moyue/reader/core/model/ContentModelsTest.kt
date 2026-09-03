package com.moyue.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentModelsTest {
    @Test
    fun readerPositionUsesBlocksInsteadOfTxtBytes() {
        val position = ReaderPosition(blockIndex = 3, charOffset = 12)

        assertEquals(3, position.blockIndex)
        assertEquals(12, position.charOffset)
    }

    @Test
    fun parsedTxtChapterKeepsRawByteOffsets() {
        val chapter = ParsedChapter(
            title = "第二章",
            index = 1,
            startByte = 27,
            endByte = 81,
            cachePath = null,
        )

        assertEquals(27L, chapter.startByte)
        assertEquals(81L, chapter.endByte)
        assertTrue(chapter.cachePath == null)
    }
}
