package com.moyue.reader.parser.epub

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class EpubPackageParserTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesEpubWhenOptionalXmlFeaturesAreUnsupported() {
        val root = temporaryFolder.newFolder("unpacked")
        root.resolve("META-INF").mkdirs()
        root.resolve("OEBPS").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
        )
        root.resolve("OEBPS/book.opf").writeText(
            """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>兼容性测试</dc:title></metadata><manifest><item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
        )
        root.resolve("OEBPS/one.xhtml").writeText(
            """<?xml version="1.0"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html xmlns="http://www.w3.org/1999/xhtml"><body><p>正文</p></body></html>""",
        )
        val factoryCalls = AtomicInteger(0)
        val parser = EpubPackageParser {
            factoryCalls.incrementAndGet()
            UnsupportedOptionalFeaturesFactory()
        }

        val parsed = parser.parse(root)

        assertEquals("兼容性测试", parsed.title)
        assertEquals(listOf("one.xhtml"), parsed.spine.map { it.href })
        assertEquals(2, factoryCalls.get())
    }

    private class UnsupportedOptionalFeaturesFactory(
        private val delegate: DocumentBuilderFactory = DocumentBuilderFactory.newInstance(),
    ) : DocumentBuilderFactory() {
        override fun newDocumentBuilder(): DocumentBuilder {
            delegate.isNamespaceAware = isNamespaceAware
            return delegate.newDocumentBuilder()
        }

        override fun setAttribute(name: String, value: Any?) = delegate.setAttribute(name, value)

        override fun getAttribute(name: String): Any = delegate.getAttribute(name)

        override fun setFeature(name: String, value: Boolean) {
            if (name.contains("disallow-doctype-decl")) {
                throw ParserConfigurationException("Feature is not supported: $name")
            }
            delegate.setFeature(name, value)
        }

        override fun getFeature(name: String): Boolean = delegate.getFeature(name)
    }
}
