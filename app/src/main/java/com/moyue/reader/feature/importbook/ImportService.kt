package com.moyue.reader.feature.importbook

import android.content.Context
import android.net.Uri
import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.storage.AtomicImportCoordinator
import com.moyue.reader.core.storage.ImportProcessor
import com.moyue.reader.core.storage.ImportRequest
import com.moyue.reader.core.storage.RecoverableImportException
import com.moyue.reader.parser.BookParser
import com.moyue.reader.parser.epub.EpubBookParser
import com.moyue.reader.parser.txt.TxtBookParser
import com.moyue.reader.parser.web.FetchedWebPage
import com.moyue.reader.parser.web.NoReadableContentException
import com.moyue.reader.parser.web.ReadabilityExtractor
import com.moyue.reader.parser.web.ReadablePage
import com.moyue.reader.parser.web.WebBookParser
import com.moyue.reader.parser.web.WebContentFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class WebImportPreview(val fetched: FetchedWebPage, val readable: ReadablePage)

class ImportService(
    private val context: Context,
    private val coordinator: AtomicImportCoordinator,
    private val fetcher: WebContentFetcher = WebContentFetcher(),
    private val extractor: ReadabilityExtractor = ReadabilityExtractor(),
) {
    suspend fun importDocument(
        uri: Uri,
        displayName: String?,
        onProgress: (ImportState.Running) -> Unit = {},
    ): ImportState = withContext(Dispatchers.IO) {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in setOf("txt", "epub") }
            ?: return@withContext ImportState.FatalError("unknown", "请选择 TXT 或 EPUB 文件")
        val type = if (extension == "txt") SourceType.TXT else SourceType.EPUB
        val taskId = UUID.randomUUID().toString()
        val incoming = context.cacheDir.resolve("incoming/$taskId.$extension")
        try {
            incoming.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportState.RecoverableError(taskId, "无法读取所选文件，请重新选择")
            input.use { source -> incoming.outputStream().buffered().use(source::copyTo) }
            coordinator.import(
                ImportRequest(taskId, incoming, type, extension),
                ParserProcessor(parserFor(type), type, displayName = displayName),
                onProgress,
            )
        } finally {
            incoming.delete()
        }
    }

    suspend fun previewWeb(url: String): WebImportPreview {
        val fetched = fetcher.fetch(url.trim())
        return WebImportPreview(fetched, extractor.extract(fetched.html, fetched.finalUrl))
    }

    suspend fun importWeb(
        preview: WebImportPreview,
        onProgress: (ImportState.Running) -> Unit = {},
    ): ImportState = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val incoming = context.cacheDir.resolve("incoming/$taskId.html")
        try {
            incoming.parentFile?.mkdirs()
            incoming.writeText(preview.fetched.html)
            coordinator.import(
                ImportRequest(taskId, incoming, SourceType.WEB, "html", preview.fetched.finalUrl),
                ParserProcessor(WebBookParser(extractor), SourceType.WEB, preview.fetched.finalUrl),
                onProgress,
            )
        } finally {
            incoming.delete()
        }
    }

    private fun parserFor(type: SourceType): BookParser = when (type) {
        SourceType.TXT -> TxtBookParser()
        SourceType.EPUB -> EpubBookParser()
        SourceType.WEB -> WebBookParser(extractor)
    }
}

private class ParserProcessor(
    private val parser: BookParser,
    private val sourceType: SourceType,
    private val sourceUrl: String? = null,
    private val displayName: String? = null,
) : ImportProcessor {
    override suspend fun detect(source: File) = Unit

    override suspend fun parse(source: File, sourceType: SourceType): ParsedBook = try {
        parser.parse(ParseInput(source, this.sourceType, sourceUrl, displayName))
    } catch (error: NoReadableContentException) {
        throw RecoverableImportException(error.message ?: "未识别到正文", error)
    } catch (error: Exception) {
        if (this.sourceType == SourceType.EPUB) {
            throw RecoverableImportException("EPUB 解析失败，请确认文件完整且未加密", error)
        }
        throw error
    }

    override suspend fun validate(book: ParsedBook) {
        if (book.title.isBlank()) throw RecoverableImportException("未识别到书名")
        if (book.chapters.isEmpty()) throw RecoverableImportException("未识别到章节正文")
    }
}
