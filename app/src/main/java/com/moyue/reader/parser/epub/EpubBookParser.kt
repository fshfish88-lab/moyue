package com.moyue.reader.parser.epub

import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.ParsedChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EpubBookParser(
    private val extractor: SafeEpubExtractor = SafeEpubExtractor(),
    private val packageParser: EpubPackageParser = EpubPackageParser(),
) : BookParser {
    override suspend fun parse(input: ParseInput): ParsedBook = withContext(Dispatchers.IO) {
        require(input.sourceType == SourceType.EPUB) { "EpubBookParser only accepts EPUB" }
        val cacheRoot = requireNotNull(input.source.parentFile).resolve("derived/epub")
        val unpacked = cacheRoot.resolve("unpacked")
        val normalized = cacheRoot.resolve("normalized")
        extractor.extract(input.source, unpacked)
        normalized.mkdirs()
        val pkg = packageParser.parse(unpacked)
        var wordCount = 0L
        val chapters = pkg.spine.mapIndexed { index, item ->
            val htmlFile = packageParser.safeResolve(pkg.packageDirectory, item.href)
            require(htmlFile.isFile) { "EPUB 章节缺失: ${item.href}" }
            val html = htmlFile.readText()
            val title = pkg.chapterTitles[htmlFile.canonicalPath].orEmpty().ifBlank { HtmlTextNormalizer.title(html) }.ifBlank { "第 ${index + 1} 章" }
            val text = HtmlTextNormalizer.toPlainText(html)
            wordCount += text.count { !it.isWhitespace() }
            val output = normalized.resolve(index.toString().padStart(5, '0') + ".txt")
            output.writeText(text)
            ParsedChapter(
                title = title,
                index = index,
                startByte = null,
                endByte = null,
                cachePath = output.absolutePath,
            )
        }
        val coverPath = pkg.cover?.let { cover ->
            packageParser.safeResolve(pkg.packageDirectory, cover.href).takeIf(File::isFile)?.absolutePath
        }
        ParsedBook(
            title = pkg.title,
            author = pkg.author,
            sourceType = SourceType.EPUB,
            chapters = chapters,
            coverPath = coverPath,
            wordCount = wordCount,
        )
    }
}

internal object HtmlTextNormalizer {
    fun title(html: String): String {
        val value = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?: ""
        return decodeEntities(value.replace(Regex("(?is)<[^>]+>"), " ")).trim()
    }

    fun toPlainText(html: String): String = decodeEntities(
        html
            .replace(Regex("(?is)<(head|script|style|nav|aside)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?is)<img[^>]*alt=[\"']([^\"']*)[\"'][^>]*>"), "\n[图片：\$1]\n")
            .replace(Regex("(?is)</?(p|div|section|article|h[1-6]|blockquote|li|br|hr)[^>]*>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), ""),
    ).lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value }
}
