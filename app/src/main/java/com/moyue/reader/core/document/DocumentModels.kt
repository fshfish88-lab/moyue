package com.moyue.reader.core.document

import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.model.SourceType
import java.io.File

enum class DocumentFormat { TXT, EPUB, WEB, MARKDOWN, PDF, IMAGE, COMIC }
data class DocumentMetadata(val format: String, val mimeType: String, val byteSize: Long, val pageCount: Int? = null, val locked: Boolean = false)
data class ReaderCapabilities(val search: Boolean, val outline: Boolean, val thumbnails: Boolean, val bookmarks: Boolean, val typography: Boolean, val zoom: Boolean, val edit: Boolean)
data class LibraryItem(val book: BookEntity, val format: DocumentFormat)
object ReaderEngineRegistry {
    fun item(book: BookEntity) = LibraryItem(book, when(book.sourceType) {
        SourceType.TXT -> DocumentFormat.TXT
        SourceType.EPUB -> DocumentFormat.EPUB
        SourceType.WEB -> DocumentFormat.WEB
        SourceType.MARKDOWN -> DocumentFormat.MARKDOWN
        SourceType.DOCUMENT -> when(File(book.sourcePath).extension.lowercase()) {
            "jpg", "jpeg", "png", "webp" -> DocumentFormat.IMAGE
            "cbz", "zip" -> DocumentFormat.COMIC
            else -> DocumentFormat.PDF
        }
    })
    fun capabilities(format: DocumentFormat) = when(format) {
        DocumentFormat.IMAGE -> ReaderCapabilities(false, false, false, false, false, true, false)
        DocumentFormat.COMIC -> ReaderCapabilities(false, false, true, true, false, true, false)
        DocumentFormat.PDF -> ReaderCapabilities(true, true, true, true, false, true, false)
        DocumentFormat.MARKDOWN -> ReaderCapabilities(true, true, false, false, true, false, true)
        else -> ReaderCapabilities(false, true, false, false, true, false, false)
    }
}
object FormatDetector {
    fun isPdf(source: File): Boolean = source.inputStream().use { input ->
        val header = ByteArray(1024); val count = input.read(header)
        count > 0 && String(header, 0, count, Charsets.ISO_8859_1).contains("%PDF-")
    }
}
/** Logical page coordinates survive screen size, density and WebView changes. */
data class PdfPosition(val pageIndex: Int = 0, val left: Float = 0f, val top: Float = 0f, val scale: String = "page-width") {
    fun safe(pageCount: Int): PdfPosition = copy(
        pageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        left = left.takeIf { it.isFinite() } ?: 0f,
        top = top.takeIf { it.isFinite() } ?: 0f,
        scale = scale.takeIf { it in setOf("page-width", "page-fit", "auto") || (it.toFloatOrNull()?.let { v -> v.isFinite() && v in .1f..10f } == true) } ?: "page-width",
    )
}
