package com.moyue.reader.parser.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebCatalogV152Test {
    private val source="https://example.com/book/index_1.html"
    @Test fun combinesSeparateVolumesInsteadOfKeepingOnlyTheLargestList() {
        val html="<div class='chapter-list'><a href='1.html'>开篇</a><a href='2.html'>雨夜</a></div>"+
            "<div class='chapter-list'><a href='3.html'>归来</a><a href='4.html'>前路</a><a href='5.html'>落幕</a></div>"
        assertEquals(listOf("开篇","雨夜","归来","前路","落幕"),WebCatalogExtractor().chapters(html,source).map {it.title})
    }
    @Test fun includesSingleChapterVolumesAndDeduplicatesNestedLists() {
        val html="<div id='list'><dl class='chapter-list'><a href='1.html'>第1章</a><a href='2.html'>第2章</a></dl></div>"+
            "<div class='chapter-list'><a href='3.html'>第3章</a></div><div class='latest'><a href='9.html'>第9章 最新</a></div>"
        assertEquals(listOf("第1章","第2章","第3章"),WebCatalogExtractor().chapters(html,source).map {it.title})
    }
    @Test fun currentDirectoryIsNotReplacedByALatestOnlyBookSummary()=runBlocking {
        val html="<a href='./'>目录</a><div id='list'><a href='1.html'>第1章</a><a href='2.html'>第2章</a></div><a href='index_2.html'>下一页</a>"
        val result=WebCatalogLoader({url->FetchedWebPage(url,if(url.endsWith("index_2.html"))"<div id='list'><a href='3.html'>第3章</a></div>" else "<div class='latest'><a href='99.html'>第99章</a><a href='98.html'>第98章</a><a href='97.html'>第97章</a></div>")}).load(FetchedWebPage(source,html))
        assertTrue(result.entries.any {it.title=="第1章"});assertTrue(result.entries.any {it.title=="第3章"})
    }
    @Test fun followsRangeLinksAndDataValueDirectoryOptions() {
        val html="<a href='index_2.html'>第51章 - 第100章</a><select><option data-value='index_3.html'>第101-150章</option></select>"
        assertEquals(listOf("https://example.com/book/index_2.html","https://example.com/book/index_3.html"),WebCatalogExtractor().directoryPages(html,source))
    }
    @Test fun directoryPageLinksNeverBecomeChapterEntries() {
        val html="<div id='list'><a href='1.html'>第1章</a><a href='2.html'>第2章</a><a href='index_2.html'>第51章 - 第100章</a></div>"
        assertEquals(2,WebCatalogExtractor().chapters(html,source).size)
    }
    @Test fun numericOptionsUseTheCurrentDirectoryPagePattern() {
        assertEquals(listOf("https://example.com/book/index_2.html","https://example.com/book/index_3.html"),
            WebCatalogExtractor().directoryPages("<select><option value='1'>1</option><option value='2'>2</option><option value='3'>3</option></select>",source))
    }
    @Test fun digitByDigitChineseChapterNumbersSortAsWholeNumbers() {
        val entries=WebCatalogExtractor().chapters("<div id='list'><a href='1827'>第一八二七章</a><a href='18'>第一八章</a><a href='1'>第一章</a></div>",source)
        assertEquals(listOf("第一章","第一八章","第一八二七章"),entries.map {it.title})
    }
    @Test fun aTailOnlyCatalogIsNeverMarkedComplete()=runBlocking {
        val result=WebCatalogLoader({error("no linked pages")}).load(FetchedWebPage(source,"<div id='list'><a href='1827.html'>第一八二七章 别来无恙</a><a href='1828.html'>第一八二八章 牧神</a></div>"))
        assertFalse(result.complete)
    }
}
