package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition

data class ReaderPage(
    val text: String,
    val start: ReaderPosition,
    val end: ReaderPosition,
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
