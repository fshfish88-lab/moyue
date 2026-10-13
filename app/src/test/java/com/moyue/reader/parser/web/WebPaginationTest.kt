package com.moyue.reader.parser.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebPaginationTest {
    private val base = "https://pagination.example"
    private fun fixture(name: String) = javaClass.getResource("/web/pagination/$name")!!.readText()

    @Test fun siblingDirectoryPagesRecoverAll137ChaptersWithoutFetchingBodies() = runBlocking {
        val calls = mutableListOf<String>()
        val result = WebCatalogLoader({ url ->
            calls += url
            val number = Regex("/4200_(\\d+)/").find(url)!!.groupValues[1].toInt()
            FetchedWebPage(url, fixture(if (number == 1) "catalog.html" else "catalog-$number.html"))
        }).load(FetchedWebPage("$base/4200/", fixture("catalog.html")))
        assertEquals(137, result.entries.size)
        assertTrue(result.complete)
        assertEquals("$base/4200/8001.html", result.entries.first().url)
        assertEquals("$base/4200/8137.html", result.entries.last().url)
        assertTrue(calls.all { it.matches(Regex("https://pagination.example/4200_\\d+/")) })
    }

    @Test fun siblingDirectoryDoesNotAdmitAnotherBook() {
        val html = "<select><option value='/4201_2/'>第51 - 100章</option></select>" +
            "<a href='/4200/8001.html'>第1章 正文</a><a href='/4201/1.html'>第2章 别的书</a>"
        val parser = WebCatalogExtractor()
        assertTrue(parser.directoryPages(html, "$base/4200_2/").isEmpty())
        assertEquals(listOf("$base/4200/8001.html"), parser.chapters(html, "$base/4200_2/").map { it.url })
    }

    @Test fun declaredChapterCountPreventsFalseCompleteOnMissingPagination() = runBlocking {
        val html = "<div>共 137 章</div><a href='1.html'>第1章 开篇</a><a href='2.html'>第2章 正文</a>"
        val result = WebCatalogLoader({ error("no more links") }).load(FetchedWebPage("$base/4200/", html))
        assertFalse(result.complete)
    }

    @Test fun nextPageIsNotExposedAsNextChapter() {
        val page = ReadabilityExtractor().extract(fixture("chapter.html"), "$base/4200/8001.html")
        assertNull(page.nextUrl)
    }

    /** End-to-end catalog and body navigation using generated offline fixtures. */
    @Test fun generatedFixturesContainAllCatalogEntriesAndBothContentPages() = runBlocking {
        val calls = mutableListOf<String>()
        val fetch: suspend (String) -> FetchedWebPage = { url ->
            calls += url
            val name = when {
                url.endsWith("8001.html") -> "chapter.html"
                url.endsWith("8001_2.html") -> "chapter-2.html"
                url.endsWith("/4200/") || url.endsWith("/4200_1/") -> "catalog.html"
                else -> "catalog-${Regex("/4200_(\\d+)/").find(url)!!.groupValues[1]}.html"
            }
            FetchedWebPage(url, fixture(name))
        }
        val initial = fetch("$base/4200/8001.html")
        val catalog = WebCatalogLoader(fetch).load(initial)
        assertEquals(137, catalog.entries.size)
        assertTrue(catalog.complete)
        calls.clear()
        val result = WebChapterLoader(fetch).load(initial, catalog.entries[1].url)
        val first = ReadabilityExtractor().extract(initial.html, initial.finalUrl)
        assertEquals(listOf("$base/4200/8001_2.html"), calls)
        assertTrue(result.text.startsWith(first.text))
        assertTrue(result.text.length > first.text.length)
        assertEquals(catalog.entries[1].url, result.nextUrl)
    }
}
