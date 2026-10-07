package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        assertEquals(listOf("第一章 开端", "第二章 归途"), parsed.chapters.map { it.title })
        assertTrue(parsed.chapters.all { File(requireNotNull(it.cachePath)).exists() })
        assertTrue(File(parsed.chapters[0].cachePath!!).readText().contains("第一段正文"))
    }

    @Test
    fun parsesConfiguredRealWorldSample() = runBlocking {
        val configured = System.getenv("MOYUE_EPUB_SAMPLE").orEmpty()
        assumeTrue("Set MOYUE_EPUB_SAMPLE to run the real-world EPUB test", configured.isNotBlank())
        val source = File(configured)
        assumeTrue("Configured EPUB sample does not exist", source.isFile)
        val epub = temporaryFolder.newFile("real-world.epub")
        source.copyTo(epub, overwrite = true)

        val parsed = EpubBookParser().parse(ParseInput(epub, SourceType.EPUB))

        assertTrue(parsed.title.isNotBlank())
        assertTrue(parsed.chapters.isNotEmpty())
        assertTrue(parsed.chapters.all { File(requireNotNull(it.cachePath)).isFile })
        assertTrue(parsed.wordCount > 0)
        val root = epub.parentFile.resolve("derived/epub/unpacked")
        val packageParser = EpubPackageParser()
        val pkg = packageParser.parse(root)
        val images = pkg.spine.flatMap { item ->
            val document = packageParser.safeResolve(pkg.packageDirectory, item.href)
            EpubContentParser.parse(document.readText(), document, root).filterIsInstance<com.moyue.reader.core.model.ContentBlock.Image>()
        }
        assertTrue("Real EPUB illustrations should survive parsing", images.isNotEmpty())
        assertTrue("All illustration paths must resolve after extraction", images.all { File(it.localPath).isFile })
        println("REAL_EPUB_IMAGES count=${images.size}")
        println("REAL_EPUB_RESULT title=${parsed.title} author=${parsed.author} chapters=${parsed.chapters.size} words=${parsed.wordCount}")
    }

    private fun makeEpub(): File {
        val file = temporaryFolder.newFile("sample.epub")
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>风雪夜归人</dc:title><dc:creator>墨客</dc:creator></metadata><manifest><item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>第一章 开端</text></navLabel><content src="one.xhtml"/></navPoint><navPoint><navLabel><text>第二章 归途</text></navLabel><content src="two.xhtml"/></navPoint></navMap></ncx>""",
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
