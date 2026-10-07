package com.moyue.reader.parser.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebCatalogLoaderTest {
    @Test fun loadsEveryPageBeyondTheOldThirtyPageLimitAndKeepsNaturalOrder()=runBlocking {
        val calls=mutableListOf<String>()
        val loader=WebCatalogLoader({url->calls+=url
            val page=url.substringAfter("index_").substringBefore('.').toInt()
            FetchedWebPage(url,"<div id='list'><a href='${page*2}.html'>第${page*2}章</a><a href='${page*2-1}.html'>第${page*2-1}章</a></div>"+
                if(page<34)"<a href='index_${page+1}.html'>下一页 &gt;&gt;</a>" else "")
        })
        val result=loader.load(FetchedWebPage("https://example.com/book/22.html","<a href='index_1.html'>章节目录</a>"))
        assertTrue(result.complete);assertEquals(68,result.entries.size);assertEquals(34,calls.size)
        assertEquals("第1章",result.entries.first().title);assertEquals("第68章",result.entries.last().title)
    }
    @Test fun directoryUrlInputUsesItsOwnPageAndFollowsOptionsAndNumericLinks()=runBlocking {
        val source="https://example.com/book/index_1.html"
        val html="<div id='list'><a href='1.html'>开篇</a><a href='2.html'>风雨</a></div><select><option value='index_2.html'>3-4章</option></select><a href='/index.html'>首页</a>"
        val calls=mutableListOf<String>()
        val result=WebCatalogLoader({url->calls+=url;FetchedWebPage(url,"<div id='list'><a href='3.html'>明日</a><a href='4.html'>新生</a></div><a href='index_1.html'>1</a>")}).load(FetchedWebPage(source,html))
        assertEquals(listOf("https://example.com/book/index_2.html"),calls)
        assertEquals(listOf("开篇","风雨","明日","新生"),result.entries.map {it.title});assertTrue(result.complete)
    }
    @Test fun latestThreeChaptersAreMergedWithTheFullDirectory()=runBlocking {
        val source="https://example.com/book/"
        val first="<a href='4.html'>第4章 最新</a><a href='3.html'>第3章</a><a href='2.html'>第2章</a><a href='index_1.html'>全部章节</a>"
        val result=WebCatalogLoader({url->FetchedWebPage(url,(1..4).joinToString("") {"<a href='$it.html'>第${it}章</a>"})}).load(FetchedWebPage(source,first))
        assertTrue(result.complete);assertEquals(4,result.entries.size);assertEquals("第1章",result.entries.first().title)
    }
    @Test fun failedDirectoryPagesNeverProduceACompleteMarker()=runBlocking {
        val source="https://example.com/book/index_1.html"
        val html="<a href='1'>第1章</a><a href='2'>第2章</a><a href='index_2.html'>下一页</a>"
        val result=WebCatalogLoader({throw java.io.IOException("offline")}).load(FetchedWebPage(source,html))
        assertFalse(result.complete);assertEquals(2,result.entries.size)
    }
    @Test fun limitedOrCyclicCatalogRemainsBounded()=runBlocking {
        val source="https://example.com/book/index_1.html"
        val html="<a href='1'>第1章</a><a href='2'>第2章</a><a href='index_2.html'>下一页</a>"
        val result=WebCatalogLoader({error("should not fetch")},maxPages=1).load(FetchedWebPage(source,html))
        assertFalse(result.complete)
    }
}
