package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition

internal fun safeReaderPosition(blocks: List<ContentBlock>, blockIndex: Int, charOffset: Int): ReaderPosition {
    if (blockIndex !in blocks.indices) return ReaderPosition(0, 0)
    val length = when (val block = blocks[blockIndex]) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.Heading -> block.text.length
        is ContentBlock.Quote -> block.text.length
        else -> 0
    }
    return ReaderPosition(blockIndex, charOffset.coerceIn(0, length))
}
