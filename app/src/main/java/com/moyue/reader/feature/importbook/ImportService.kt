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
            ?.takeIf { it in setOf("txt", "epub", "md", "markdown", "pdf", "jpg", "jpeg", "png", "webp", "cbz", "zip") }
            ?: return@withContext ImportState.FatalError("unknown", "请选择 TXT / EPUB / Markdown / PDF / 图片 / CBZ / 图片 ZIP 文件")
        val type = when (extension) { "txt" -> SourceType.TXT; "epub" -> SourceType.EPUB; "md", "markdown" -> SourceType.MARKDOWN; else -> SourceType.DOCUMENT }
        val storedExtension = if (type == SourceType.MARKDOWN) "md" else extension
        val taskId = UUID.randomUUID().toString()
        val incoming = context.cacheDir.resolve("incoming/$taskId.$extension")
        try {
            incoming.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportState.RecoverableError(taskId, "无法读取所选文件，请重新选择")
            input.use { source ->
                incoming.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192); var copied = 0L
                    while (true) {
                        val count = source.read(buffer); if (count < 0) break
                        copied += count
                        if (type == SourceType.MARKDOWN) require(copied <= com.moyue.reader.parser.markdown.MarkdownText.MAX_BYTES) { "Markdown 文件不能超过 5 MB" }
                        if (type == SourceType.DOCUMENT) require(copied <= 200L * 1024 * 1024) { "文档或图片文件不能超过 200 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            coordinator.import(
                ImportRequest(taskId, incoming, type, storedExtension),
                if(extension == "pdf") com.moyue.reader.feature.pdf.PdfImportProcessor(context, displayName)
                else if(type == SourceType.DOCUMENT) com.moyue.reader.feature.image.VisualImportProcessor(displayName, extension in setOf("cbz", "zip"))
                else ParserProcessor(parserFor(type), type, displayName = displayName),
                onProgress,
            )
        } catch (error: kotlinx.coroutines.CancellationException) { throw error
        } catch (error: Exception) {
            ImportState.RecoverableError(taskId, error.message ?: "文件导入失败")
        } finally {
            incoming.delete()
        }
    }

    suspend fun newMarkdown(): ImportState = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val incoming = context.cacheDir.resolve("incoming/$taskId.md")
        try {
            incoming.parentFile?.mkdirs(); incoming.writeText("")
            coordinator.import(ImportRequest(taskId, incoming, SourceType.MARKDOWN, "md"),
                ParserProcessor(com.moyue.reader.parser.markdown.MarkdownBookParser(), SourceType.MARKDOWN, displayName = "未命名.md"))
        } finally { incoming.delete() }
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
        SourceType.MARKDOWN -> com.moyue.reader.parser.markdown.MarkdownBookParser()
        SourceType.DOCUMENT -> error("页面文档使用独立导入处理器")
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
