package com.moyue.reader.parser.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TxtEncodingDetectorTest {
    private val detector = TxtEncodingDetector()

    @Test
    fun detectsUtf8WithoutBom() {
        val text = "第一章 开始\n这是正文"
        val result = detector.detect(text.toByteArray(Charsets.UTF_8))

        assertEquals(text, result.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(TxtEncoding.UTF8, result.encoding)
    }

    @Test
    fun detectsUtf16BomAndRemovesBomFromDecodedText() {
        val text = "第一章 开始\n这是正文"
        val body = text.toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        val result = detector.detect(bytes)

        assertEquals(TxtEncoding.UTF16_LE, result.encoding)
        assertEquals(text, result.decode(bytes))
    }

    @Test
    fun decodesGbkAndGb2312WithoutReplacementCharacters() {
        val text = "第一章 开始\n这是一个测试故事"
        listOf("GBK", "GB2312").forEach { name ->
            val bytes = text.toByteArray(Charset.forName(name))
            val result = detector.detect(bytes)
            assertEquals(text, result.decode(bytes))
            assertTrue(result.encoding in setOf(TxtEncoding.GBK, TxtEncoding.GB2312))
        }
    }

    @Test
    fun distinguishesTraditionalBig5Sample() {
        val text = "第一章 開始\n這是一個測試故事，閱讀下一頁"
        val bytes = text.toByteArray(Charset.forName("Big5"))
        val result = detector.detect(bytes)

        assertEquals(TxtEncoding.BIG5, result.encoding)
        assertEquals(text, result.decode(bytes))
    }
}
