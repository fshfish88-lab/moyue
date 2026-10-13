package com.moyue.reader.parser.web

import java.io.IOException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Loads one logical chapter; the fetch function shares the reader's HTTP/browser session. */
class WebChapterLoader(
    private val fetch: suspend (String) -> FetchedWebPage,
    private val extractor: ReadabilityExtractor = ReadabilityExtractor(),
    private val maxPages: Int = 100,
) {
    suspend fun load(initial: FetchedWebPage, nextChapterUrl: String? = null): ReadablePage {
        val first = extractor.extract(initial.html, initial.finalUrl)
        val title = chapterTitle(first.title)
        val pages = mutableListOf(first)
        val visited = mutableSetOf(initial.finalUrl.substringBefore('#'))
        var current = first
        var nextChapter = first.nextUrl ?: nextChapterUrl
        while (true) {
            coroutineContext.ensureActive()
            val next = current.nextPageUrl ?: break
            if (next == nextChapterUrl?.substringBefore('#')) {
                nextChapter = next
                break
            }
            if (next in visited) throw IOException("章节分页链接循环，请重试或检查源网页")
            if (pages.size >= maxPages) throw IOException("章节分页超过 $maxPages 页，未保存不完整正文")
            visited += next
            val fetched = fetch(next)
            val finalUrl = fetched.finalUrl.substringBefore('#')
            if (finalUrl == nextChapterUrl?.substringBefore('#')) {
                nextChapter = finalUrl
                break
            }
            if (finalUrl != next && !visited.add(finalUrl)) throw IOException("章节分页跳回了已读取页面")
            val part = extractor.extract(fetched.html, finalUrl)
            if (chapterTitle(part.title) != title) {
                // Some sites call the next chapter 'next page'. Never append its body.
                if (pageNumbers(current.title)?.let { it.first < it.second } == true) {
                    throw IOException("章节分页跳到了不同章节，未保存不完整正文")
                }
                nextChapter = finalUrl
                break
            }
            pages += part
            current = part
            nextChapter = part.nextUrl ?: nextChapterUrl
        }
        if (pageNumbers(current.title)?.let { it.first < it.second } == true) {
            throw IOException("章节分页缺失，请重试或检查源网页")
        }
        return first.copy(title = title, text = pages.joinToString("\n\n") { it.text },
            nextUrl = nextChapter, nextPageUrl = null, removedItems = pages.flatMap { it.removedItems }.distinct())
    }

    private fun pageNumbers(title: String): Pair<Int, Int>? = PAGE_SUFFIX.find(title)?.let {
        it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }

    companion object {
        private val PAGE_SUFFIX = Regex("\\s*[（(]\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[)）]\\s*$")
        internal fun chapterTitle(title: String) = title.replace(PAGE_SUFFIX, "").trim()
    }
}
