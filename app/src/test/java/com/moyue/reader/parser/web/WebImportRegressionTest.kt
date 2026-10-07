package com.moyue.reader.parser.web

import java.net.ServerSocket
import java.net.InetAddress
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebImportRegressionTest {
    private fun fetched(html:String,encoding:String,type:String="text/html"):String {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val worker=Thread {
            server.accept().use {socket->
            val reader=socket.getInputStream().bufferedReader()
            while(!reader.readLine().isNullOrEmpty())Unit
            val bytes=html.toByteArray(Charset.forName(encoding))
            socket.getOutputStream().use {it.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());it.write(bytes)}
            }
        }
        worker.isDaemon=true;worker.start()
        return try {runBlocking {WebContentFetcher().fetch("http://127.0.0.1:${server.localPort}/").html}} finally {server.close();worker.join(1000)}
    }
    @Test fun honorsGbkMetaWithoutHttpCharset() {
        val html="<meta charset='gbk'><h1>第一章 风雪夜</h1><article>这是完整中文正文。</article>"
        assertEquals(html,fetched(html,"GBK"))
    }
    @Test fun detectsLegacyGb18030WithoutDeclaration() {
        val html="<h1>第一章 风雪夜</h1><article>这是完整中文正文。</article>"
        assertEquals(html,fetched(html,"GB18030"))
    }
    @Test fun readsHttpEquivAndQuotedCharsetWithWhitespace() {
        val html="<meta content='text/html; charset=gb2312' http-equiv='Content-Type'><h1>第一章 开始</h1>"
        assertEquals(html,fetched(html,"GBK","text/html; charset = \"GBK\""))
    }
    @Test fun directoryLabelsCanIncludeDecorationsAndUnquotedHref() {
        assertEquals("https://example.com/book/",WebCatalogExtractor().directoryUrl("<a href=/book/>查看全部章节 &gt;&gt;</a>","https://example.com/book/1.html"))
    }
    @Test fun chapterListAllowsPlainTitlesAndIgnoresLatestAndRecommendations() {
        val html="<aside><a href='/other/1'>第1章 其他小说</a></aside><div id='latest'><a href='3'>第3章 最新</a></div><div id='list'><dl><dd><a href='1'>风雪夜</a></dd><dd><a href='2'>初次相遇</a></dd><dd><a href='3'>继续前行</a></dd></dl></div>"
        assertEquals(listOf("风雪夜","初次相遇","继续前行"),WebCatalogExtractor().chapters(html,"https://example.com/book/").map {it.title})
    }
    @Test fun chapterEntitiesAndCompactPaginationWork() {
        val parser=WebCatalogExtractor()
        assertEquals("第一章 初见",parser.chapters("<a href='1'>&#x7B2C;一章 初见</a>","https://example.com/book/").single().title)
        assertEquals("https://example.com/book/list/2",parser.nextDirectoryPage("<a rel=next href='2'>下一页 &raquo;</a>","https://example.com/book/list/1"))
    }
}
