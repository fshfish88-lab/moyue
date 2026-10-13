package com.moyue.reader.parser.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebChapterLoaderTest {
    private val base = "https://example.com/book/"
    private fun page(n: Int, total: Int = 3, next: String? = if (n < total) "1_${n + 1}.html" else null) =
        FetchedWebPage(base + if (n == 1) "1.html" else "1_$n.html",
            "<h1>第1章 测试($n/$total)</h1><article><p>" + "第${n}部分的测试正文。".repeat(5) + "</p></article>" +
                (next?.let { "<a href='$it'>下一页</a>" } ?: "<a href='2.html'>下一章</a>"))

    @Test fun joinsThreePartsInOrderAndStopsBeforeNextChapter() = runBlocking {
        val calls = mutableListOf<String>()
        val merged = WebChapterLoader({ url -> calls += url; page(if (url.endsWith("_2.html")) 2 else 3) })
            .load(page(1), base + "2.html")
        assertEquals(listOf(base + "1_2.html", base + "1_3.html"), calls)
        assertEquals("第1章 测试", merged.title)
        assertEquals(base + "1.html", merged.sourceUrl)
        assertEquals(base + "2.html", merged.nextUrl)
        assertTrue(merged.text.indexOf("第1部分") < merged.text.indexOf("第2部分"))
        assertTrue(merged.text.indexOf("第2部分") < merged.text.indexOf("第3部分"))
    }

    @Test fun observedTwoPageStructureMergesWithoutFetchingNextChapter() = runBlocking {
        val source = "https://pagination.example/4200/8001.html"
        fun fixture(name: String) = javaClass.getResource("/web/pagination/$name")!!.readText()
        val calls = mutableListOf<String>()
        val result = WebChapterLoader({ url -> calls += url; FetchedWebPage(url, fixture("chapter-2.html")) })
            .load(FetchedWebPage(source, fixture("chapter.html")), "https://pagination.example/4200/8002.html")
        assertEquals(listOf("https://pagination.example/4200/8001_2.html"), calls)
        assertTrue(result.text.contains("第一页内容"))
        assertTrue(result.text.contains("第二页内容"))
    }

    @Test fun knownNextChapterIsNeverFetchedEvenWhenLabeledNextPage() = runBlocking {
        val result = WebChapterLoader({ error("must stop at chapter boundary") })
            .load(page(1, total = 1, next = "2.html"), base + "2.html")
        assertEquals(base + "2.html", result.nextUrl)
    }

    @Test fun titleBoundaryProtectsUnknownCatalog() = runBlocking {
        val result = WebChapterLoader({ FetchedWebPage(it, "<h1>第2章 新章</h1><article>" + "下一章正文不会合并。".repeat(8) + "</article>") })
            .load(page(1, total = 1, next = "2.html"))
        assertFalse(result.text.contains("下一章正文"))
        assertEquals(base + "2.html", result.nextUrl)
    }

    @Test fun failedMiddlePageDoesNotReturnPartialSuccess() = runBlocking {
        val result = runCatching { WebChapterLoader({ throw java.io.IOException("offline") }).load(page(1)) }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test fun cycleDoesNotReturnPartialSuccess() = runBlocking {
        val result = runCatching { WebChapterLoader({ page(2, next = "1.html") }).load(page(1)) }
        assertTrue(result.isFailure)
    }

    @Test fun pageLimitDoesNotReturnPartialSuccess() = runBlocking {
        val result = runCatching { WebChapterLoader({ error("must not fetch") }, maxPages = 1).load(page(1)) }
        assertTrue(result.isFailure)
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val result = runCatching { WebChapterLoader({ throw CancellationException() }).load(page(1)) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
