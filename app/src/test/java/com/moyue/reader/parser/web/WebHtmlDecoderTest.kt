package com.moyue.reader.parser.web

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class WebHtmlDecoderTest {
    @Test fun utf8BomWinsOverAnIncorrectHeader() {
        val text="<h1>第一章 😀</h1>"
        assertEquals(text,WebHtmlDecoder.decode(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+text.toByteArray(),"text/html; charset=gbk"))
    }
    @Test fun utf16BomPreservesChinese() {
        val text="<h1>中文正文</h1>"
        assertEquals(text,WebHtmlDecoder.decode(byteArrayOf(0xff.toByte(),0xfe.toByte())+text.toByteArray(Charset.forName("UTF-16LE")),"text/html"))
    }
    @Test fun invalidDeclaredUtf8FallsBackWithoutReplacementCharacters() {
        val text="<meta charset=gbk><h1>完整中文正文</h1>"
        assertEquals(text,WebHtmlDecoder.decode(text.toByteArray(Charset.forName("GBK")),"text/html; charset=utf-8"))
    }
    @Test fun unsupportedDeclarationStillAcceptsStrictUtf8() {
        val text="<meta charset=unknown><p>中文与😀表情</p>"
        assertEquals(text,WebHtmlDecoder.decode(text.toByteArray(),"text/html"))
    }
    @Test fun malformedInputIsRejectedInsteadOfSilentlySavedAsReplacementCharacters() {
        assertTrue(runCatching {WebHtmlDecoder.decode(byteArrayOf(0xff.toByte()),"text/html; charset=utf-8")}.isFailure)
    }
}
