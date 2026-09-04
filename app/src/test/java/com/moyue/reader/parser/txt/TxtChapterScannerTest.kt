package com.moyue.reader.parser.txt

import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class TxtChapterScannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scanner = TxtChapterScanner()

    @Test
    fun gbkOffsetsAreRawBytesNotCharacters() {
        val charset = Charset.forName("GBK")
        val text = "第一章 开始\r\n你好世界\r\n第二章 后续"
        val bytes = text.toByteArray(charset)

        val chapters = scanner.scan(bytes.inputStream(), charset)

        val second = chapters[1]
        assertArrayEquals(
            "第二章 后续".toByteArray(charset),
            bytes.copyOfRange(second.startByte.toInt(), second.endByte.toInt()),
        )
    }

    @Test
    fun recognizesChineseEnglishAndSpecialChapterTitles() {
        val text = "序章\n开端\n第十二章 风雪\n正文\nChapter 203 Return\nEnd\n番外 小记\n尾声"

        val chapters = scanner.scan(text.byteInputStream(), Charsets.UTF_8)

        assertEquals(listOf("序章", "第十二章 风雪", "Chapter 203 Return", "番外 小记", "尾声"), chapters.map { it.title })
    }

    @Test
    fun createsSingleBodyChapterWhenNoHeadingExists() {
        val text = "只是正文第一段\n\n第二段"

        val chapters = scanner.scan(text.byteInputStream(), Charsets.UTF_8)

        assertEquals(1, chapters.size)
        assertEquals("正文", chapters.single().title)
        assertEquals(0, chapters.single().startByte)
        assertEquals(text.toByteArray().size.toLong(), chapters.single().endByte)
    }

    @Test
    fun loaderBuildsTransientBlocksWithoutDatabaseRows() = runBlocking {
        val source = temporaryFolder.newFile("book.txt")
        source.writeText("第一章\n第一段。\n\n第二段。", Charsets.UTF_8)
        val chapter = ChapterEntity(
            id = 9,
            bookId = 1,
            title = "第一章",
            chapterIndex = 0,
            startByte = 0,
            endByte = source.length(),
            cachePath = null,
            sourceUrl = null,
            previousUrl = null,
            nextUrl = null,
        )

        val loaded = TxtChapterLoader().load(source, chapter, Charsets.UTF_8)

        assertEquals(9, loaded.id)
        assertEquals(2, loaded.blocks.size)
        assertTrue(loaded.blocks.all { it is ContentBlock.Text })
    }

    @Test
    fun parserUsesOriginalDisplayNameAfterSourceIsStaged() = runBlocking {
        val stagedSource = temporaryFolder.newFile("source.txt")
        stagedSource.writeText("第一章 开始\n正文。", Charsets.UTF_8)

        val parsed = TxtBookParser().parse(
            ParseInput(
                source = stagedSource,
                sourceType = SourceType.TXT,
                displayName = "墨阅测试小说.txt",
            ),
        )

        assertEquals("墨阅测试小说", parsed.title)
    }
}
