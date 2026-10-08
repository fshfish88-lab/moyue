package com.moyue.reader.parser.web

import java.net.URI

class NoReadableContentException(message: String = "未识别到可阅读正文") : Exception(message)

data class ReadablePage(
    val title: String,
    val text: String,
    val sourceUrl: String,
    val previousUrl: String?,
    val nextUrl: String?,
    val removedItems: List<String>,
)

class ReadabilityExtractor {
    fun extract(html: String, sourceUrl: String): ReadablePage {
        val base = validateUrl(sourceUrl)
        val removed = mutableListOf<String>()
        var cleaned = html
        val structuralNoise = listOf("script", "style", "noscript", "nav", "header", "footer", "form", "aside", "iframe")
        structuralNoise.forEach { tag ->
            val regex = Regex("(?is)<$tag\\b[^>]*>.*?</$tag>")
            if (regex.containsMatchIn(cleaned)) removed += tag
            cleaned = cleaned.replace(regex, "")
        }
        val keywordNoise = Regex(
            "(?is)<(div|section|ul)\\b[^>]*(?:class|id)=[\"'][^\"']*(?:advert|ads?|comment|recommend|related|share|sidebar|toolbar|login)[^\"']*[\"'][^>]*>.*?</\\1>",
        )
        if (keywordNoise.containsMatchIn(cleaned)) removed += "广告/评论/推荐"
        cleaned = cleaned.replace(keywordNoise, "")

        val candidate = firstBody(cleaned, "article")
            ?: firstBody(cleaned, "main")
            ?: bestDenseBlock(cleaned)
            ?: cleaned.substringAfter("<body", cleaned).substringAfter('>', cleaned).substringBeforeLast("</body>", cleaned)
        val text = htmlToText(candidate)
        if (text.count { !it.isWhitespace() } < 20) throw NoReadableContentException()
        val title = heading(html,base.toString()).ifBlank {
            documentTitle(html).substringBefore("_").substringBefore(" · ").substringBefore(" - ").ifBlank { "网页小说" }
        }
        return ReadablePage(
            title = title,
            text = text,
            sourceUrl = base.toString(),
            previousUrl = findChapterLink(html, base, previous = true),
            nextUrl = findChapterLink(html, base, previous = false),
            removedItems = removed.distinct(),
        )
    }

    private fun firstBody(html: String, tag: String): String? =
        balancedBodies(html, tag).firstOrNull()

    private fun bestDenseBlock(html: String): String? =
        sequenceOf("div", "section")
            .flatMap { balancedBodies(html, it).asSequence() }
            .filter { htmlToText(it).length >= 20 }
            .maxByOrNull { block ->
                htmlToText(block).length - Regex("(?is)<a\\b[^>]*>.*?</a>").findAll(block).sumOf { htmlToText(it.value).length * 2 }
            }

    private fun balancedBodies(html: String, tag: String): List<String> {
        data class OpenTag(val contentStart: Int)
        data class Body(val start: Int, val content: String)

        val tags = Regex("(?is)<(/?)$tag\\b[^>]*>")
        val stack = ArrayDeque<OpenTag>()
        val bodies = mutableListOf<Body>()
        tags.findAll(html).forEach { match ->
            if (match.groupValues[1].isEmpty()) {
                stack.addLast(OpenTag(match.range.last + 1))
            } else if (stack.isNotEmpty()) {
                val open = stack.removeLast()
                bodies += Body(open.contentStart, html.substring(open.contentStart, match.range.first))
            }
        }
        return bodies.sortedBy(Body::start).map(Body::content)
    }

    private fun heading(html: String,source: String): String {
        // A product/article header contains the real title; remove only site navigation.
        val doc=org.jsoup.Jsoup.parse(html)
        doc.select("nav,footer,aside,.sidebar,.header,.logo,.header_logo,.header-logo,#header,.recommend,.comments").remove()
        val headings=doc.select("h1,h2,h3").filter {element->
            element.select("a[href]").none {WebHtml.resolve(source,it.attr("href"))!=source.substringBefore('#')}
        }
        headings.firstOrNull {WebCatalogExtractor().isChapterTitle(it.text())}?.let {return it.text()}
        doc.select("main h1,article h1,.product-title,.book-title,.entry-title,.bookinfo h1,#info h1").firstOrNull {it in headings || it.select("a[href]").isEmpty()}?.let {return it.text()}
        headings.firstOrNull {it.tagName()=="h1"}?.let {return it.text()}
        if(doc.title().isBlank())headings.firstOrNull()?.let {return it.text()}
        return ""
    }

    private fun documentTitle(html: String): String =
        Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let(::htmlToText).orEmpty()

    private fun findChapterLink(html: String, base: URI, previous: Boolean): String? {
        val anchors = Regex("(?is)<a\\b([^>]*)>(.*?)</a>").findAll(html)
        val words = if (previous) listOf("上一章", "上一页", "前一章", "prev") else listOf("下一章", "下一页", "后一章", "next")
        anchors.forEach { match ->
            val attributes = match.groupValues[1]
            val label = htmlToText(match.groupValues[2]) + " " + attributes
            if (words.any { label.contains(it, ignoreCase = true) }) {
                val href = Regex("(?is)href\\s*=\\s*[\"']([^\"']+)[\"']").find(attributes)?.groupValues?.get(1)
                    ?: return@forEach
                val resolved = runCatching { base.resolve(href) }.getOrNull() ?: return@forEach
                if ((resolved.scheme.equals("http", true) || resolved.scheme.equals("https", true)) && resolved.toString().substringBefore('#') != base.toString().substringBefore('#')) return resolved.toString()
            }
        }
        return null
    }

    private fun validateUrl(value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("网页地址无效") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "仅支持 HTTP/HTTPS 地址" }
        require(!uri.host.isNullOrBlank()) { "网页地址缺少域名" }
        return uri
    }

    private fun htmlToText(value: String): String = decodeEntities(
        value
            .replace(Regex("(?is)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</blockquote>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), ""),
    ).lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value }
}
