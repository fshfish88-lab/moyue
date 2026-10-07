package com.moyue.reader.core.document

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfPositionTest {
    @Test fun restoresLogicalPageWithinChangedDocumentBounds() {
        assertEquals(8, PdfPosition(500).safe(9).pageIndex)
        assertEquals(0, PdfPosition(-4).safe(0).pageIndex)
        assertEquals(0f, PdfPosition(left=Float.NaN,top=Float.POSITIVE_INFINITY).safe(10).top)
        assertEquals("page-width", PdfPosition(scale="javascript:bad").safe(10).scale)
        assertEquals("2.25", PdfPosition(scale="2.25").safe(10).scale)
    }
    @Test fun rejectsMislabeledBinaryWithoutPdfHeader() {
        val file=File.createTempFile("format-test",".pdf")
        try { file.writeText("hello"); assertFalse(FormatDetector.isPdf(file)); file.writeText("%PDF-1.7\n"); assertTrue(FormatDetector.isPdf(file)) } finally {file.delete()}
    }
    @Test fun pdfCapabilitiesDoNotExposeBookTypographyOrEditing() {
        val caps=ReaderEngineRegistry.capabilities(DocumentFormat.PDF)
        assertTrue(caps.zoom && caps.search && caps.bookmarks)
        assertFalse(caps.typography || caps.edit)
    }
}
