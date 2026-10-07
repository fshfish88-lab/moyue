package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ContentBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubContentParserTest {
    private val root = File("build/test-epub").absoluteFile
    private val document = root.resolve("Text/chapter.xhtml")
    @Test fun retainsImagesInOrderWithoutAltAndDecodesRelativePaths() {
        val blocks = EpubContentParser.parse("<p>前文</p><img src='../Images/a%20b.png'/><p>后文</p>", document, root)
        assertEquals(ContentBlock.Text("前文"), blocks[0])
        assertEquals(root.resolve("Images/a b.png").canonicalPath, (blocks[1] as ContentBlock.Image).localPath)
        assertEquals(ContentBlock.Text("后文"), blocks[2])
    }
    @Test fun supportsSvgImageWrapper() {
        val blocks = EpubContentParser.parse("<svg><image xlink:href='../Images/cover.jpg' /></svg>", document, root)
        assertEquals(root.resolve("Images/cover.jpg").canonicalPath, (blocks.single() as ContentBlock.Image).localPath)
    }
    @Test fun rejectsRemoteAndEscapingPaths() {
        val blocks = EpubContentParser.parse("<img src='../../private.png'/><img src='https://example.com/a.png'/>", document, root)
        assertTrue(blocks.none { it is ContentBlock.Image })
    }
}
