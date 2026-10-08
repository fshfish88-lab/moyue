package com.moyue.reader.parser.web

import java.net.URI
import kotlinx.coroutines.CancellationException

data class WebCatalogResult(val entries:List<WebCatalogEntry>,val complete:Boolean,val directoryUrl:String?)

/** Fetch only directory metadata. Chapter bodies remain lazy, even for a complete catalog. */
class WebCatalogLoader(
    private val fetch:suspend (String)->FetchedWebPage,
    private val parser:WebCatalogExtractor=WebCatalogExtractor(),
    private val maxPages:Int=200,
    private val maxEntries:Int=50_000,
) {
    suspend fun load(initial:FetchedWebPage,catalogUrl:String?=null,trace:WebCatalogTrace?=null):WebCatalogResult {
        val path=URI(initial.finalUrl).path.orEmpty()
        val directory=catalogUrl ?: initial.finalUrl.takeIf {parser.chapters(initial.html,it).isNotEmpty() && Regex("(?:index|list|catalog|directory)[^/]*$",RegexOption.IGNORE_CASE).containsMatchIn(path)}
            ?: initial.finalUrl.takeIf {parser.isDirectoryPage(initial.html,it)}
            ?: parser.directoryUrl(initial.html,initial.finalUrl)
            ?: initial.finalUrl.takeIf {parser.chapters(initial.html,it).size>=2 && path.endsWith('/')}
            ?: return WebCatalogResult(emptyList(),false,null)
        trace?.record("CATALOG",directory)
        val prefix=URI(directory).path.orEmpty().substringBeforeLast('/',"")+"/"
        val pending=ArrayDeque<String>();pending.add(directory)
        val visited=mutableSetOf<String>();val entries=linkedMapOf<String,WebCatalogEntry>()
        var complete=true
        while(pending.isNotEmpty()) {
            if(visited.size>=maxPages){complete=false;break}
            val url=pending.removeFirst()
            if(!visited.add(url))continue
            val page=try {if(url==initial.finalUrl)initial else fetch(url)}
                catch(e:CancellationException){throw e} catch(_:Exception){complete=false;continue}
            visited.add(page.finalUrl)
            val found=parser.chapters(page.html,page.finalUrl)
            trace?.record("PAGE",page.finalUrl,"entries=${found.size} first=${found.firstOrNull()?.title} last=${found.lastOrNull()?.title}")
            if(found.isEmpty())complete=false
            for(entry in found) {
                if(entries.size>=maxEntries && entry.url !in entries){complete=false;break}
                entries.putIfAbsent(entry.url,entry)
            }
            val nextPages=parser.directoryPages(page.html,page.finalUrl)
            nextPages.forEach {trace?.record("NEXT_PAGE",it)}
            for(next in nextPages) {
                // A footer's site-wide home/index page is never part of this book's directory.
                if(URI(next).path.orEmpty().startsWith(prefix) && next !in visited && next !in pending)pending.add(next)
            }
        }
        val ordered=parser.order(entries.values.toList())
        val finished=complete && !parser.startsAfterFirstChapter(ordered)
        trace?.record("RESULT",directory,"entries=${ordered.size} pages=${visited.size} complete=$finished")
        return WebCatalogResult(ordered,finished,directory)
    }
}
