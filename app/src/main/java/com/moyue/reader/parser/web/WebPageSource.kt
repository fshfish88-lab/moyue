package com.moyue.reader.parser.web
interface WebPageSource {
 val supportsRendering: Boolean get() = false
 suspend fun fetch(url: String): FetchedWebPage
 suspend fun render(url: String): FetchedWebPage = fetch(url)
}
