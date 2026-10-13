package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ContentBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubContentParserTest {
    private val root = File("build/test-epub").absoluteFile
    private val document = root.resolve("Text/chapter.xhtml")
    @Test fun retainsIllustrationsInsideTableCells() {
        val blocks = EpubContentParser.parse("<table><tr><td>前文<img src='../Images/a.png'>后文</td></tr></table>", document, root)
        assertEquals(ContentBlock.Text("前文"), blocks.first())
        assertTrue(blocks[1] is ContentBlock.Image)
        assertEquals(ContentBlock.Text("后文"), blocks.last())
    }
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
    @Test fun retainsSemanticBlocksAndDoesNotFlattenTableCellsOrDiscardFootnotes() {
        val blocks = EpubContentParser.parse("<h2>小标题</h2><p>正文<strong>加粗文字</strong>。</p><blockquote>引用段落</blockquote><hr/>" +
            "<table><tr><th>项目</th><th>结果</th></tr><tr><td>甲</td><td>乙</td></tr></table><aside epub:type='footnote'><p>注释内容</p></aside>", document, root)
        assertEquals(ContentBlock.Heading("小标题", 2), blocks[0])
        assertEquals(ContentBlock.Text("正文加粗文字。"), blocks[1])
        assertEquals(ContentBlock.Quote("引用段落"), blocks[2])
        assertTrue(blocks.contains(ContentBlock.Divider))
        assertTrue(blocks.contains(ContentBlock.Text("项目 | 结果")))
        assertTrue(blocks.contains(ContentBlock.Text("甲 | 乙")))
        assertTrue(blocks.contains(ContentBlock.Text("注释内容")))
    }
    @Test fun handlesUnquotedImagesEntitiesAndNestedParagraphsInDocumentOrder() {
        val blocks = EpubContentParser.parse("<section><p>甲&#x1F30D;&nbsp;乙</p><div><img src=../Images/a.png alt=插图></div><p>末段<br>下一行</p></section>",document,root)
        assertEquals(ContentBlock.Text("甲🌍 乙"),blocks[0])
        assertEquals("插图",(blocks[1] as ContentBlock.Image).description)
        assertEquals(listOf(ContentBlock.Text("末段"),ContentBlock.Text("下一行")),blocks.drop(2))
    }
}
