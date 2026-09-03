package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookParserTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesMetadataAndSpineOrderAndWritesNormalizedCache() = runBlocking {
        val epub = makeEpub()

        val parsed = EpubBookParser().parse(ParseInput(epub, SourceType.EPUB))

        assertEquals("风雪夜归人", parsed.title)
        assertEquals("墨客", parsed.author)
        assertEquals(listOf("开端", "归途"), parsed.chapters.map { it.title })
        assertTrue(parsed.chapters.all { File(requireNotNull(it.cachePath)).exists() })
        assertTrue(File(parsed.chapters[0].cachePath!!).readText().contains("第一段正文"))
    }

    private fun makeEpub(): File {
        val file = temporaryFolder.newFile("sample.epub")
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>风雪夜归人</dc:title><dc:creator>墨客</dc:creator></metadata><manifest><item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/one.xhtml" to "<html><head><title>开端</title></head><body><h1>开端</h1><p>第一段正文。</p></body></html>",
            "OEBPS/two.xhtml" to "<html><head><title>归途</title></head><body><p>第二段正文。</p></body></html>",
        )
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
