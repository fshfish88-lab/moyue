package com.moyue.reader.parser.markdown

import org.junit.Assert.*
import org.junit.Test
import com.moyue.reader.feature.markdown.MarkdownRevisionGate

class MarkdownTextTest {
    @Test fun preservesSourceTextIncludingCrLf() {
        val text = "---\r\ntitle: 原文\r\n---\r\n# 标题\r\n\r\n- [ ] 待办\r\n```md\r\n# 不是标题\r\n```\r\n"
        assertEquals(text, MarkdownText.decode(text.toByteArray()))
    }
    @Test fun decodesBomWithoutChangingOriginalBytes() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "中文".toByteArray()
        assertEquals("中文", MarkdownText.decode(bytes))
        assertEquals(0xef.toByte(), bytes.first())
    }
    @Test fun rejectsNonUtf8InsteadOfSilentlyReplacingCharacters() {
        assertThrows(Exception::class.java) { MarkdownText.decode(byteArrayOf(0xff.toByte())) }
    }
    @Test fun rejectsBinaryAndLargeFiles() {
        assertThrows(IllegalArgumentException::class.java) { MarkdownText.decode("a\u0000b".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownText.decode(ByteArray(MarkdownText.MAX_BYTES + 1)) }
    }
    @Test fun emptyDocumentIsAllowedAndNamesAreSafe() {
        assertEquals("", MarkdownText.decode(ByteArray(0)))
        assertEquals("未命名", MarkdownText.displayTitle(null))
        assertEquals("我的笔记", MarkdownText.displayTitle("我的笔记.md"))
        assertEquals("a_b_c", MarkdownText.safeName("a/b:c"))
    }
    @Test fun staleRevisionCannotOverrideLatestButFailedWriteCanRetry() {
        val gate = MarkdownRevisionGate()
        assertTrue(gate.accepts(1)); assertTrue(gate.accepts(1))
        gate.committed(1)
        assertFalse(gate.accepts(0)); assertFalse(gate.accepts(1)); assertTrue(gate.accepts(3))
        gate.committed(3); assertFalse(gate.accepts(2))
    }
    @Test fun complexDocumentsAreReadOnlyButCodeMarkersAreNotHeadings() {
        assertFalse(MarkdownText.canEdit("# 标题\n".repeat(501)))
        assertTrue(MarkdownText.canEdit("```md\n" + "# 代码\n".repeat(501) + "```"))
        assertFalse(MarkdownText.canEdit("a".repeat(MarkdownText.EDIT_BYTES + 1)))
    }
}
