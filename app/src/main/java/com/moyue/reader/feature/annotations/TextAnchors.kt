package com.moyue.reader.feature.annotations

import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition
import java.security.MessageDigest
import java.util.Locale

fun blockText(block: ContentBlock): String = when (block) {
    is ContentBlock.Text -> block.text
    is ContentBlock.Heading -> block.text
    is ContentBlock.Quote -> block.text
    is ContentBlock.Image -> ""
    ContentBlock.Divider -> "· · ·"
}

fun textHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class TextSegment(val displayStart: Int, val displayEnd: Int, val blockIndex: Int, val sourceStart: Int) {
    fun position(offset: Int) = ReaderPosition(blockIndex, sourceStart + (offset - displayStart).coerceIn(0, displayEnd - displayStart))
}

/** Synthetic indentation/separators never become source characters. */
fun mappedPosition(segments: List<TextSegment>, offset: Int, fallback: ReaderPosition): ReaderPosition {
    val segment = segments.firstOrNull { offset >= it.displayStart && offset < it.displayEnd }
        ?: segments.firstOrNull { it.displayStart >= offset }
        ?: segments.lastOrNull() ?: return fallback
    return segment.position(offset)
}

fun sourceSelection(blocks: List<ContentBlock>, start: ReaderPosition, end: ReaderPosition): String {
    if (start.blockIndex > end.blockIndex || start.blockIndex !in blocks.indices || end.blockIndex !in blocks.indices) return ""
    return (start.blockIndex..end.blockIndex).joinToString("\n") { i ->
        val text = blockText(blocks[i])
        text.substring(if (i == start.blockIndex) start.charOffset.coerceIn(0, text.length) else 0,
            if (i == end.blockIndex) end.charOffset.coerceIn(0, text.length) else text.length)
    }
}

/** Safe FTS terms for CJK and Latin substrings, independent of platform dictionaries. */
object SearchTerms {
    private fun points(text: String) = text.lowercase(Locale.ROOT).codePoints().toArray()
    private fun term(a: Int, b: Int? = null) = "u" + a.toString(16) + (b?.let { "x" + it.toString(16) } ?: "")
    fun index(text: String): String {
        val p = points(text)
        return buildSet {
            p.forEach { add(term(it)) }
            for (i in 0 until p.size - 1) add(term(p[i], p[i + 1]))
        }.joinToString(" ")
    }
    fun query(text: String): String {
        val p = points(text.trim().take(120))
        if (p.isEmpty()) return ""
        return (if (p.size == 1) listOf(term(p[0])) else (0 until p.size - 1).map { term(p[it], p[it + 1]) })
            .distinct().joinToString(" ")
    }
}
