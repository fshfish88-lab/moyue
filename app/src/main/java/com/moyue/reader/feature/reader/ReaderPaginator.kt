package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition

data class ReaderPage(
    val text: String,
    val start: ReaderPosition,
    val end: ReaderPosition,
    val image: ContentBlock.Image? = null,
)

class ReaderPaginator {
    fun paginate(blocks: List<ContentBlock>, maxCharactersPerPage: Int): List<ReaderPage> {
        require(maxCharactersPerPage > 0) { "page capacity must be positive" }
        val texts = blocks.map(::plainText)
        val fullText = texts.joinToString("\n")
        if (fullText.isEmpty()) return listOf(ReaderPage("", ReaderPosition(0, 0), ReaderPosition(0, 0)))
        val blockStarts = buildList {
            var offset = 0
            texts.forEachIndexed { index, text ->
                add(offset)
                offset += text.length + if (index < texts.lastIndex) 1 else 0
            }
        }
        return buildList {
            var start = 0
            while (start < fullText.length) {
                val end = minOf(start + maxCharactersPerPage, fullText.length)
                add(
                    ReaderPage(
                        text = fullText.substring(start, end),
                        start = positionFor(start, texts, blockStarts),
                        end = positionFor(end, texts, blockStarts),
                    ),
                )
                start = end
            }
        }
    }

    fun paginateMeasured(blocks: List<ContentBlock>, indent: Boolean, separator: String = "\n\n", fits: (String) -> Boolean): List<ReaderPage> {
        val original = blocks.map(::plainText)
        val prefixes = blocks.map { if (indent && it is ContentBlock.Text) "　　" else "" }
        val texts = original.mapIndexed { i, text -> prefixes[i] + text.trimStart() }
        val starts = mutableListOf<Int>()
        var total = 0
        texts.forEach { starts += total; total += it.length + separator.length }
        val full = texts.joinToString(separator)
        fun position(offset: Int): ReaderPosition {
            val i = starts.indexOfLast { it <= offset }.coerceAtLeast(0)
            if (texts.isEmpty()) return ReaderPosition(0, 0)
            val leading = original[i].length - original[i].trimStart().length
            return ReaderPosition(i, (offset - starts[i] - prefixes[i].length + leading).coerceIn(0, original[i].length))
        }
        if (full.isEmpty()) return listOf(ReaderPage("", ReaderPosition(0, 0), ReaderPosition(0, 0)))
        return buildList {
            var start = 0
            while (start < full.length) {
                var low = start + 1
                var high = full.length
                var end = start
                while (low <= high) {
                    val mid = (low + high) / 2
                    if (fits(full.substring(start, mid))) { end = mid; low = mid + 1 } else high = mid - 1
                }
                // Do not split UTF-16 surrogate pairs between pages.
                if (end < full.length && end > start && full[end - 1].isHighSurrogate() && full[end].isLowSurrogate()) end--
                require(end > start) { "阅读区域太小，请减小字号或边距" }
                add(ReaderPage(full.substring(start, end), position(start), position(end)))
                start = end
            }
        }
    }

    private fun positionFor(
        globalOffset: Int,
        texts: List<String>,
        blockStarts: List<Int>,
    ): ReaderPosition {
        val index = blockStarts.indexOfLast { it <= globalOffset }.coerceAtLeast(0)
        return ReaderPosition(index, (globalOffset - blockStarts[index]).coerceIn(0, texts[index].length))
    }

    private fun plainText(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Heading -> block.text
        is ContentBlock.Quote -> block.text
        is ContentBlock.Image -> block.description.orEmpty()
        ContentBlock.Divider -> ""
    }
}
