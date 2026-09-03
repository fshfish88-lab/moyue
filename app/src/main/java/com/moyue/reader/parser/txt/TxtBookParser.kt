package com.moyue.reader.parser.txt

import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.ParsedChapter
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

class TxtBookParser(
    private val detector: TxtEncodingDetector = TxtEncodingDetector(),
    private val scanner: TxtChapterScanner = TxtChapterScanner(),
) : BookParser {
    override suspend fun parse(input: ParseInput): ParsedBook = withContext(Dispatchers.IO) {
        require(input.sourceType == SourceType.TXT) { "TxtBookParser only accepts TXT" }
        val sample = input.source.inputStream().buffered().use { stream ->
            val buffer = ByteArray(256 * 1024)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        }
        val encoding = detector.detect(sample)
        val chapters = input.source.inputStream().use { scanner.scan(it, encoding.charset) }
        ParsedBook(
            title = input.source.nameWithoutExtension.ifBlank { "未命名书籍" },
            author = null,
            sourceType = SourceType.TXT,
            chapters = chapters.mapIndexed { index, chapter ->
                ParsedChapter(
                    title = chapter.title,
                    index = index,
                    startByte = chapter.startByte,
                    endByte = chapter.endByte,
                    cachePath = null,
                )
            },
            wordCount = countCharacters(input.source, encoding.charset),
            textEncoding = encoding.charset.name(),
        )
    }

    private fun countCharacters(source: File, charset: Charset): Long {
        var count = 0L
        source.bufferedReader(charset).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                for (index in 0 until read) if (!buffer[index].isWhitespace()) count++
            }
        }
        return count
    }
}

class TxtChapterLoader {
    suspend fun load(
        source: File,
        chapter: ChapterEntity,
        charset: Charset,
    ): ReaderChapter = withContext(Dispatchers.IO) {
        val start = requireNotNull(chapter.startByte) { "TXT chapter requires startByte" }
        val end = requireNotNull(chapter.endByte) { "TXT chapter requires endByte" }
        require(start >= 0 && end >= start && end <= source.length()) { "invalid TXT byte range" }
        val bytes = readRange(source, start, end)
        val text = bytes.toString(charset).removePrefix("\uFEFF")
        val blocks = text.split(Regex("\\R\\s*\\R"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(ContentBlock::Text)
        ReaderChapter(chapter.id, chapter.title, chapter.chapterIndex, blocks)
    }

    private fun readRange(source: File, start: Long, end: Long): ByteArray {
        val output = ByteArrayOutputStream()
        RandomAccessFile(source, "r").use { file ->
            file.seek(start)
            var remaining = end - start
            val buffer = ByteArray(16 * 1024)
            while (remaining > 0) {
                val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            check(remaining == 0L) { "TXT chapter ended before expected offset" }
        }
        return output.toByteArray()
    }
}
