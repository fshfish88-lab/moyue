package com.moyue.reader.parser.web

import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.ParsedChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebBookParser(
    private val extractor: ReadabilityExtractor = ReadabilityExtractor(),
) : BookParser {
    override suspend fun parse(input: ParseInput): ParsedBook = withContext(Dispatchers.IO) {
        require(input.sourceType == SourceType.WEB) { "WebBookParser only accepts WEB" }
        val sourceUrl = requireNotNull(input.sourceUrl) { "网页导入缺少原始地址" }
        val page = extractor.extract(input.source.readText(), sourceUrl)
        val cache = requireNotNull(input.source.parentFile).resolve("derived/web/chapter-00000.txt")
        cache.parentFile?.mkdirs()
        cache.writeText(page.text)
        ParsedBook(
            title = page.title,
            author = null,
            sourceType = SourceType.WEB,
            chapters = listOf(
                ParsedChapter(
                    title = page.title,
                    index = 0,
                    startByte = null,
                    endByte = null,
                    cachePath = cache.absolutePath,
                    sourceUrl = page.sourceUrl,
                    previousUrl = page.previousUrl,
                    nextUrl = page.nextUrl,
                ),
            ),
            wordCount = page.text.count { !it.isWhitespace() }.toLong(),
        )
    }
}
