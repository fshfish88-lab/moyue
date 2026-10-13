package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.os.Bundle
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.feature.importbook.*
import com.moyue.reader.parser.web.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Real Room/import/cache path with generated offline fixtures. */
class WebPaginationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        val report = JSONObject()
        val owned = mutableListOf<Long>()
        lateinit var c: com.moyue.reader.MoyueContainer
        fun verify(value: Boolean, name: String) { check(value) { name }; report.put(name, true) }
        fun fixture(name: String) = context.assets.open("web/pagination/$name").bufferedReader().use { it.readText() }
        try {
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            runBlocking {
                val url = "https://pagination.example/4200/8001.html"
                    var offline = false
                    val requests = mutableListOf<String>()
                    val fetcher = object : WebPageSource {
                        override suspend fun fetch(url: String): FetchedWebPage {
                            requests += url
                            if (offline) throw IOException("offline fixture")
                            val name = when {
                                url.endsWith("8001.html") -> "chapter.html"
                                url.endsWith("8001_2.html") -> "chapter-2.html"
                                url.endsWith("/4200/") || url.endsWith("/4200_1/") -> "catalog.html"
                                Regex("/4200_\\d+/$").containsMatchIn(url) -> "catalog-${url.substringAfterLast("4200_").trimEnd('/')}.html"
                                else -> error("Unexpected body request: $url")
                            }
                            return FetchedWebPage(url, fixture(name))
                        }
                    }
                    val imports = ImportService(c.applicationContext, c.importCoordinator, fetcher)
                    val id = (imports.importWeb(imports.previewWeb(FetchedWebPage(url, fixture("chapter.html")))) as ImportState.Completed).bookId
                    owned += id
                    val book = requireNotNull(c.database.bookDao().get(id))
                    val old = c.database.chapterDao().forBook(id).single()
                    val oldText = File(requireNotNull(old.cachePath)).readText()
                    val sourceHash = com.moyue.reader.feature.image.VisualRepository.hash(File(book.sourcePath))
                    val progress = ReadingProgressEntity(id, old.id, 0, 8, .2f, .2f, 123)
                    c.database.readingProgressDao().upsert(progress)
                    val annotation = AnnotationEntity(bookId=id, type="BOOKMARK", anchorJson="{\"chapterId\":${old.id}}", selectedText="", note="pagination fixture", color="yellow", location="第1章", sourceHash=sourceHash, createdAt=123, updatedAt=123)
                    c.database.annotationDao().insert(annotation)
                    val annotations = c.database.annotationDao().listForBook(id)
                    c.storage.webCache(id).resolve("catalog-v9.done").writeText("complete")
                    val source = RoomReaderDataSource(c.database, c.storage, webFetcher=fetcher)
                    offline = true
                    val cached = source.load(book, old)
                    verify(cached.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text } == oldText, "legacyOfflineCacheRetained")
                    verify(!File(old.cachePath + ".pagination-v1").exists(), "failedUpgradeNotMarkedComplete")
                    offline = false
                    requests.clear()
                    val session = ReaderRepository(source).open(id)
                    verify(session.chapters.size == 137, "oldCatalogMarkerReevaluated137")
                    verify(session.chapter.id == old.id, "chapterIdPreserved")
                    val text = session.chapter.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text }
                    verify(text.startsWith(oldText) && text.contains("第二页内容"), "legacyCacheExtendedInPlace")
                    verify(c.database.readingProgressDao().get(id) == progress, "progressPreserved")
                    verify(c.database.annotationDao().listForBook(id) == annotations, "bookmarkPreserved")
                    verify(com.moyue.reader.feature.image.VisualRepository.hash(File(book.sourcePath)) == sourceHash, "originalHtmlPreserved")
                    verify(requests.filter { it.endsWith(".html") }.all { it == url || it.endsWith("8001_2.html") }, "unreadChapterBodiesNotFetched")
                    offline = true
                    requests.clear()
                    source.load(session.book, requireNotNull(c.database.chapterDao().get(old.id)))
                    verify(requests.isEmpty(), "mergedCacheWorksOffline")
                    verify(runCatching { source.load(session.book, session.chapters.last()) }.isFailure, "uncachedFailureNotSavedAsComplete")
                    // A single imported page with no directory must advance using the link
                    // found on the last content page, even when the session row is stale.
                    val loneUrl = "https://fixture.test/lone/1.html"
                    fun body(n: Int, part: Int) = "<h1>第${n}章 独立测试($part/3)</h1><article>" +
                        "独立章节第${part}部分测试正文。".repeat(6) + "</article>" +
                        if (part < 3) "<a href='${n}_${part+1}.html'>下一页</a>" else "<a href='${n+1}.html'>下一章</a>"
                    val loneFetcher = object : WebPageSource {
                        override suspend fun fetch(url: String): FetchedWebPage {
                            val match = Regex("/(\\d+)(?:_(\\d+))?\\.html$").find(url)!!
                            return FetchedWebPage(url, body(match.groupValues[1].toInt(), match.groupValues[2].toIntOrNull() ?: 1))
                        }
                    }
                    val loneImports = ImportService(c.applicationContext, c.importCoordinator, loneFetcher)
                    val loneId = (loneImports.importWeb(loneImports.previewWeb(loneUrl)) as ImportState.Completed).bookId
                    owned += loneId
                    val repository = ReaderRepository(RoomReaderDataSource(c.database, c.storage, webFetcher=loneFetcher))
                    val opened = repository.open(loneId)
                    verify(opened.chapter.blocks.filterIsInstance<ContentBlock.Text>().any { "第3部分" in it.text }, "threePartsInReader")
                    val next = requireNotNull(repository.next(0))
                    verify(next.title == "第2章 独立测试" && next.blocks.filterIsInstance<ContentBlock.Text>().any { "第3部分" in it.text }, "nextChapterUsesFinalPageLink")
            }
            out.putString("stream", "PASS pagination fixtures checks=${report.length()}")
        } catch (e: Throwable) { out.putString("stream", "FAIL ${e.stackTraceToString()}") }
        finally {
            runBlocking {
                for (id in owned) {
                    c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }
                    c.storage.bookDirectory(id).deleteRecursively()
                    c.storage.webCache(id).deleteRecursively()
                }
            }
            out.putString("checks", report.toString())
            finish(if (out.getString("stream").orEmpty().startsWith("PASS")) -1 else 0, out)
        }
    }
}
