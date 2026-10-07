package com.moyue.reader.parser.markdown

import com.moyue.reader.core.model.*
import com.moyue.reader.parser.BookParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MarkdownText {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val EDIT_BYTES = 1024 * 1024

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Markdown 文件不能超过 5 MB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!text.contains('\u0000')) { "请选择 UTF-8 Markdown 文本文件" }
        return text.removePrefix("\uFEFF")
    }

    fun displayTitle(name: String?): String = name?.substringBeforeLast('.')?.trim()
        ?.takeIf { it.isNotEmpty() } ?: "未命名"

    fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(100).ifEmpty { "未命名" }

    fun wordCount(text: String): Long = text.count { !it.isWhitespace() }.toLong()

    /** Conservative input complexity guard, not used to generate the document outline. */
    fun canEdit(text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > EDIT_BYTES) return false
        var fence: String? = null; var headings = 0
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (fence == null) fence = marker else if (fence == marker) fence = null
            } else if (fence == null && Regex("^#{1,6}\\s").containsMatchIn(trimmed) && ++headings > 500) return false
        }
        return true
    }
}

class MarkdownBookParser : BookParser {
    override suspend fun parse(input: ParseInput): ParsedBook = withContext(Dispatchers.IO) {
        require(input.sourceType == SourceType.MARKDOWN)
        require(input.source.length() <= MarkdownText.MAX_BYTES) { "Markdown 文件不能超过 5 MB" }
        val text = MarkdownText.decode(input.source.readBytes())
        ParsedBook(
            title = MarkdownText.displayTitle(input.displayName ?: input.source.name),
            author = null, sourceType = SourceType.MARKDOWN,
            chapters = listOf(ParsedChapter("全文", 0, null, null, null)),
            wordCount = MarkdownText.wordCount(text), textEncoding = "UTF-8",
        )
    }
}
