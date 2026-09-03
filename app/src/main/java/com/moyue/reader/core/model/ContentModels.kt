package com.moyue.reader.core.model

import java.io.File

enum class SourceType { TXT, EPUB, WEB }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Heading(val text: String, val level: Int = 2) : ContentBlock
    data class Image(val localPath: String, val description: String? = null) : ContentBlock
    data class Quote(val text: String) : ContentBlock
    data object Divider : ContentBlock
}

data class ReaderPosition(
    val blockIndex: Int,
    val charOffset: Int,
) {
    init {
        require(blockIndex >= 0) { "blockIndex must be non-negative" }
        require(charOffset >= 0) { "charOffset must be non-negative" }
    }
}

data class ReaderChapter(
    val id: Long,
    val title: String,
    val index: Int,
    val blocks: List<ContentBlock>,
)

data class ParsedChapter(
    val title: String,
    val index: Int,
    val startByte: Long?,
    val endByte: Long?,
    val cachePath: String?,
    val sourceUrl: String? = null,
    val previousUrl: String? = null,
    val nextUrl: String? = null,
)

data class ParsedBook(
    val title: String,
    val author: String?,
    val sourceType: SourceType,
    val chapters: List<ParsedChapter>,
    val coverPath: String? = null,
    val wordCount: Long = 0,
    val textEncoding: String? = null,
)

data class ParseInput(
    val source: File,
    val sourceType: SourceType,
    val sourceUrl: String? = null,
)
