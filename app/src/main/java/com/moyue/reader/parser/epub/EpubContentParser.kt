package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ContentBlock
import java.io.File
import java.net.URI

/** Preserve local illustrations in document order, including SVG image wrappers. */
internal object EpubContentParser {
    fun parse(html: String, document: File, root: File): List<ContentBlock> {
        val body = html.replace(Regex("(?is)<(head|script|style|nav|aside)\\b[^>]*>.*?</\\1>"), "")
        val result = mutableListOf<ContentBlock>()
        fun text(value: String) {
            HtmlTextNormalizer.toPlainText(value).split(Regex("\\R\\s*\\R"))
                .filter(String::isNotBlank).forEach { result += ContentBlock.Text(it) }
        }
        var cursor = 0
        Regex("(?is)<(?:img|image)\\b[^>]*>").findAll(body).forEach { match ->
            text(body.substring(cursor, match.range.first))
            val attributes = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
                .findAll(match.value).associate { it.groupValues[1].lowercase() to it.groupValues[3].replace("&amp;", "&") }
            val src = attributes["src"] ?: attributes["xlink:href"] ?: attributes["href"]
            val file = src?.let { resolve(it, document, root) }
            if (file != null) result += ContentBlock.Image(file.absolutePath, attributes["alt"]?.takeIf(String::isNotBlank))
            else result += ContentBlock.Text(attributes["alt"]?.takeIf(String::isNotBlank) ?: "图片路径无效")
            cursor = match.range.last + 1
        }
        text(body.substring(cursor))
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
