package com.moyue.reader.parser.epub

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubPackageCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun unpacked() = temporary.newFolder().apply {
        resolve("META-INF").mkdirs()
        resolve("OPS").mkdirs()
        resolve("META-INF/container.xml").writeText("<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>")
        resolve("OPS/book.opf").writeText("<package><metadata><title>测试</title></metadata><manifest><item id='chapter' href='one.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='chapter'/></spine></package>")
        resolve("OPS/nav.xhtml").writeText("<html><nav role='doc-toc'><a href='one.xhtml'>第一章</a></nav></html>")
    }

    @Test fun unchangedPackageIsReusedAndClearForcesReload() {
        val source = temporary.newFile()
        val root = unpacked()
        val cache = EpubPackageCache()
        val original = cache.get(source, root)
        repeat(20) { assertSame(original, cache.get(source, root)) }
        cache.clear()
        assertNotSame(original, cache.get(source, root))
    }

    @Test fun sourcePackageNavigationAndBookSwitchInvalidateMetadata() {
        val source = temporary.newFile()
        val root = unpacked()
        val cache = EpubPackageCache()
        var before = cache.get(source, root)
        source.writeText("replacement source")
        var after = cache.get(source, root)
        assertNotSame(before, after)
        before = after
        val opf = root.resolve("OPS/book.opf")
        opf.writeText(opf.readText().replace("测试", "新的测试书名"))
        after = cache.get(source, root)
        assertNotSame(before, after)
        assertEquals("新的测试书名", after.title)
        before = after
        root.resolve("OPS/nav.xhtml").writeText("<html><nav role='doc-toc'><a href='one.xhtml'>修改后的第一章</a></nav></html>")
        after = cache.get(source, root)
        assertNotSame(before, after)
        assertEquals("修改后的第一章", after.chapterTitles.values.single())
        assertNotSame(after, cache.get(temporary.newFile(), unpacked()))
    }
}
