package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ContentBlock
import java.io.File
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Preserve local illustrations in document order, including SVG image wrappers. */
internal object EpubContentParser {
    fun parse(html: String, document: File, root: File): List<ContentBlock> {
        val body = Jsoup.parse(html).body()
        body.select("script, style, nav").remove()
        val result = mutableListOf<ContentBlock>()
        val pending = StringBuilder()
        fun flush(kind: String) {
            pending.toString().replace('\u00a0', ' ').lines().map(String::trim).filter(String::isNotEmpty).forEach {
                result += when {
                    kind.matches(Regex("h[1-6]")) -> ContentBlock.Heading(it, kind.last().digitToInt())
                    kind == "blockquote" -> ContentBlock.Quote(it)
                    else -> ContentBlock.Text(it)
                }
            }
            pending.clear()
        }
        fun visit(node: Node, kind: String = "p") {
            if (node is TextNode) { pending.append(node.wholeText); return }
            if (node !is Element) return
            val tag = node.normalName()
            if (tag == "img" || tag == "image") {
                flush(kind)
                val src = listOf("src", "xlink:href", "href").firstNotNullOfOrNull { node.attr(it).takeIf(String::isNotBlank) }
                val file = src?.let { resolve(it, document, root) }
                val alt = node.attr("alt").takeIf(String::isNotBlank)
                result += if (file != null) ContentBlock.Image(file.absolutePath, alt) else ContentBlock.Text(alt ?: "图片路径无效")
                return
            }
            if (tag == "hr") { flush(kind); result += ContentBlock.Divider; return }
            if (tag == "br") { flush(kind); return }
            if (tag == "tr") {
                flush(kind)
                val cells = node.children().filter { it.normalName() in setOf("td", "th") }
                if (node.select("img, image").isNotEmpty()) {
                    cells.forEach { visit(it, kind) }
                    flush(kind)
                } else if (cells.isNotEmpty()) result += ContentBlock.Text(cells.joinToString(" | ") { it.text() })
                return
            }
            val boundary = node.isBlock
            val nextKind = if (tag.matches(Regex("h[1-6]")) || tag == "blockquote") tag else kind
            if (boundary) flush(kind)
            node.childNodes().forEach { visit(it, nextKind) }
            if (boundary) flush(nextKind)
        }
        visit(body)
        flush("p")
        return result
    }

    private fun resolve(src: String, document: File, root: File): File? = runCatching {
        val uri = URI(src.replace(" ", "%20"))
        require(!uri.isAbsolute && uri.rawAuthority == null)
        val file = File(document.parentFile, requireNotNull(uri.path)).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        file
    }.getOrNull()
}
