package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.AnnotationEntity
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.feature.annotations.annotationColor
import com.moyue.reader.feature.annotations.blockText
import com.moyue.reader.feature.annotations.textHash
import org.json.JSONObject

internal data class IndexedReaderAnnotation(
    val firstBlock: Int, val lastBlock: Int, val start: Int, val end: Int,
    val sourceHash: String, val color: Int,
)

/** Parse and sort once per annotation change, including temporary jump marks ordered last. */
internal fun indexReaderAnnotations(annotations: List<AnnotationEntity>): Map<Long, List<IndexedReaderAnnotation>> {
    val indexed = mutableMapOf<Long, MutableList<IndexedReaderAnnotation>>()
    annotations.filter { it.type != "BOOKMARK" }
        .sortedWith(compareBy<AnnotationEntity> { if (it.id < 0) Long.MAX_VALUE else it.updatedAt }.thenBy { it.id })
        .forEach { annotation ->
            val anchor = runCatching { JSONObject(annotation.anchorJson) }.getOrNull() ?: return@forEach
            if (anchor.optString("kind") != "TEXT") return@forEach
            val first = anchor.optInt("blockIndex")
            indexed.getOrPut(anchor.optLong("chapterId")) { mutableListOf() }.add(IndexedReaderAnnotation(
                first, anchor.optInt("endBlock", first), anchor.optInt("charOffset"), anchor.optInt("endOffset"),
                annotation.sourceHash, annotationColor(annotation.color),
            ))
        }
    return indexed
}

/** Validate source hashes once per chapter and index ranges by source paragraph. */
internal fun chapterHighlights(chapter: ReaderChapter, annotations: List<IndexedReaderAnnotation>): Map<Int, List<HighlightRange>> {
    val hashes = mutableMapOf<Int, String>()
    val ranges = mutableMapOf<Int, List<HighlightRange>>()
    for (mark in annotations) {
        if (mark.firstBlock !in chapter.blocks.indices ||
            mark.sourceHash != hashes.getOrPut(mark.firstBlock) { textHash(blockText(chapter.blocks[mark.firstBlock])) }) continue
        for (block in mark.firstBlock..minOf(mark.lastBlock, chapter.blocks.lastIndex)) {
            val length = blockText(chapter.blocks[block]).length
            val start = (if (block == mark.firstBlock) mark.start else 0).coerceIn(0, length)
            val end = (if (block == mark.lastBlock) mark.end else length).coerceIn(0, length)
            if (start < end) ranges[block] = overlayHighlight(ranges[block].orEmpty(), HighlightRange(start, end, mark.color))
        }
    }
    return ranges
}
