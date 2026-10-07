package com.moyue.reader.parser.web
import org.junit.Assert.*
import org.junit.Test
class WebCatalogExtractorTest {
 @Test fun latestSectionAndBonusDoNotDisplaceTheFirstChapter() {
  val entries=WebCatalogExtractor().chapters("<a href='999'>第九百九十九章 最新</a><a href='bonus'>番外：第九章睡觉关灯</a><a href='1'>第一章 开始</a><a href='2'>第二章 继续</a>", "https://example.com/")
  assertEquals(listOf("第一章 开始", "第二章 继续", "第九百九十九章 最新", "番外：第九章睡觉关灯"), entries.map { it.title })
 }

 @Test fun findsDirectoryAndSortsDeduplicatedChapters() {
  val parser = WebCatalogExtractor()
  assertEquals("https://example.com/book/", parser.directoryUrl("<a href='../'>目录</a>", "https://example.com/book/ch/1.html"))
  val html = "<a href='2.html'>第二章 夜路</a><a href='1.html'>第一章 初见</a><a href='2.html'>第二章 夜路</a><a href='https://evil.test/3'>第三章</a><a href='login'>登录</a>"
  val chapters = parser.chapters(html, "https://example.com/book/")
  assertEquals(listOf("第一章 初见", "第二章 夜路"), chapters.map { it.title })
  assertEquals("https://example.com/book/1.html", chapters.first().url)
 }
 @Test fun supportsArabicAndPaginatedCatalog() {
  val parser=WebCatalogExtractor()
  assertEquals(listOf("第2章 B", "第10章 A"), parser.chapters("<a href='10'>第10章 A</a><a href='2'>第2章 B</a>", "https://example.com/").map { it.title })
  assertEquals("https://example.com/list/2", parser.nextDirectoryPage("<a href='2'>下一页</a>", "https://example.com/list/1"))
 }
}
