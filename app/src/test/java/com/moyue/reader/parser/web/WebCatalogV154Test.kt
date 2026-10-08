package com.moyue.reader.parser.web
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class WebCatalogV154Test {
 @Test fun directoryLabelCanContainBookName() {
  val source="https://example.com/novel/664.html"
  assertEquals("https://example.com/novel/index_1.html",WebCatalogExtractor().directoryUrl("<a href='index_1.html'>测试小说全部章节目录</a>",source))
 }
 @Test fun latestListCannotMaskCompleteTableCatalog() {
  val html="<div id='list'><h2>最新章节</h2><a href='1061.html'>第1061章 最新</a><a href='1062.html'>第1062章 最新</a></div><table id='all-chapters'><tbody><tr>"+(1..1080).joinToString(""){"<td><a href='$it.html'>第${it}章 正文</a></td>"}+"</tr></tbody></table>"
  val chapters=WebCatalogExtractor().chapters(html,"https://example.com/novel/")
  assertEquals(1080,chapters.size);assertEquals("第1章 正文",chapters.first().title)
 }
 @Test fun explicitCatalogOptionUrlsAreNotGuessedFromIndexNaming() {
  val html="<select class='chapter-pages'><option value='toc-a.html'>1-30章</option><option value='toc-b.html'>31-60章</option></select>"
  assertEquals(listOf("https://example.com/novel/toc-b.html"),WebCatalogExtractor().directoryPages(html,"https://example.com/novel/toc-a.html"))
 }
 @Test fun standaloneCompleteDirectoryDoesNotRequireAnIndexFileName()=runBlocking {
  val html="<div id='list'>"+(1..100).joinToString(""){"<a href='chapter-$it.html'>第${it}回 正文</a>"}+"</div>"
  val result=WebCatalogLoader({error("should use provided HTML")}).load(FetchedWebPage("https://example.com/novel/book.aspx",html))
  assertEquals(100,result.entries.size);assertTrue(result.complete)
 }
 @Test fun explicitDirectoryOverridesLatestFragment()=runBlocking {
  val initial=FetchedWebPage("https://example.com/novel/664.html","<div id='list'><a href='1061.html'>第1061章</a></div>")
  val calls=mutableListOf<String>()
  val result=WebCatalogLoader({url->calls+=url;FetchedWebPage(url,"<div id='list'>"+(1..1080).joinToString(""){"<a href='$it.html'>第${it}章</a>"}+"</div>")})
   .load(initial,"https://example.com/novel/toc-a.html")
  assertEquals(listOf("https://example.com/novel/toc-a.html"),calls);assertEquals(1080,result.entries.size);assertTrue(result.complete)
 }
 @Test fun parentBreadcrumbDoesNotReplaceActualBookCatalog() {
  val html="<a href='/guji/'>古籍名著目录</a><a href='index_1.html'>西游记全部章节目录</a>"
  assertEquals("https://example.com/guji/xiyouji/index_1.html",WebCatalogExtractor().directoryUrl(html,"https://example.com/guji/xiyouji/902.html"))
 }
 @Test fun diagnosticRedactsCredentialsAndTokenQuery() {
  val trace=WebCatalogTrace();trace.record("REQUEST","https://name:secret@example.com/book/index.html?page=2&token=hidden")
  val text=trace.text();assertTrue(text.contains("page=2"));assertFalse(text.contains("secret"));assertFalse(text.contains("hidden"))
 }

 @Test fun bookTitleIsNotTakenFromSidebarRecommendations() {
  val html="<title>西游记在线阅读 - 天涯书库</title><header><h1>天涯书库</h1></header><div class='widget'><h3>推荐小说</h3></div><main><header class='product-header'><h1 class='product-title'>西游记</h1></header><div>"+"这是足够长的简介文本。".repeat(20)+"</div></main>"
  assertEquals("西游记",ReadabilityExtractor().extract(html,"https://example.com/book/").title)
 }
 @Test fun chapterTitleWinsOverHeaderBranding() {
  val html="<title>西游记 : 第001回 正文_在线阅读</title><div class='header-logo'><h1>天涯书库</h1></div><article><header class='product-header'><h1 class='product-title'>第001回 正文</h1></header><p>"+"这是足够长的小说正文。".repeat(20)+"</p></article>"
  assertEquals("第001回 正文",ReadabilityExtractor().extract(html,"https://example.com/book/1.html").title)
 }

 @Test fun linkedChapterCardsDoNotBecomeTheCatalogBookTitle() {
  val html="<title>西游记在线阅读_目录</title><h2><a href='1.html'>第一回 开篇</a></h2><h2><a href='2.html'>第二回 继续</a></h2><p>"+"这是小说简介与目录页面。".repeat(20)+"</p>"
  assertEquals("西游记在线阅读",ReadabilityExtractor().extract(html,"https://example.com/book/").title)
 }

}
