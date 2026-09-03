package com.moyue.reader.parser.txt

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

data class TxtChapterIndex(
    val title: String,
    val startByte: Long,
    val endByte: Long,
)

class TxtChapterScanner {
    private val chapterRegex = Regex(
        "^(\\s{0,4})(第[\\p{IsHan}0-9一二三四五六七八九十百千万零〇两]{1,12}[章回卷节部篇].{0,40}|序章|楔子|番外.{0,20}|尾声|后记|Chapter\\s+\\d{1,6}.{0,40})$",
        RegexOption.IGNORE_CASE,
    )

    fun scan(input: InputStream, charset: Charset): List<TxtChapterIndex> {
        val headings = mutableListOf<Pair<String, Long>>()
        val newline = "\n".toByteArray(charset)
        val carriageReturn = "\r".toByteArray(charset)
        val line = ByteArrayOutputStream()
        var lineStart = 0L
        var totalBytes = 0L
        var delimiterMatch = 0

        input.buffered().use { stream ->
            while (true) {
                val next = stream.read()
                if (next == -1) break
                line.write(next)
                totalBytes++

                delimiterMatch = when {
                    next.toByte() == newline[delimiterMatch] -> delimiterMatch + 1
                    next.toByte() == newline[0] -> 1
                    else -> 0
                }
                if (delimiterMatch == newline.size) {
                    val bytesWithNewline = line.toByteArray()
                    var contentLength = bytesWithNewline.size - newline.size
                    if (bytesWithNewline.endsWithAt(carriageReturn, contentLength)) {
                        contentLength -= carriageReturn.size
                    }
                    inspectLine(bytesWithNewline.copyOf(contentLength), charset, lineStart, headings)
                    line.reset()
                    lineStart = totalBytes
                    delimiterMatch = 0
                }
            }
        }
        if (line.size() > 0) inspectLine(line.toByteArray(), charset, lineStart, headings)

        if (headings.isEmpty()) return listOf(TxtChapterIndex("正文", 0, totalBytes))
        val chapters = mutableListOf<TxtChapterIndex>()
        if (headings.first().second > 0) {
            chapters += TxtChapterIndex("正文", 0, headings.first().second)
        }
        headings.forEachIndexed { index, heading ->
            chapters += TxtChapterIndex(
                title = heading.first,
                startByte = heading.second,
                endByte = headings.getOrNull(index + 1)?.second ?: totalBytes,
            )
        }
        return chapters
    }

    private fun inspectLine(
        bytes: ByteArray,
        charset: Charset,
        startByte: Long,
        headings: MutableList<Pair<String, Long>>,
    ) {
        val title = bytes.toString(charset).removePrefix("\uFEFF").trim()
        if (title.length <= 64 && chapterRegex.matches(title)) headings += title to startByte
    }

    private fun ByteArray.endsWithAt(suffix: ByteArray, exclusiveEnd: Int): Boolean {
        if (exclusiveEnd < suffix.size) return false
        val start = exclusiveEnd - suffix.size
        return suffix.indices.all { index -> this[start + index] == suffix[index] }
    }
}
