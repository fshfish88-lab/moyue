package com.moyue.reader.parser.web

import java.net.URI

data class WebCatalogEntry(val title: String, val url: String)

class WebCatalogExtractor {
    private fun directoryAddress(url:String)=Regex("(?:index|list|catalog|directory)(?:[_./?=-]|\\.html?$|$)|[?&](?:page|p)=",RegexOption.IGNORE_CASE).containsMatchIn(url)
    private fun compact(text:String)=text.replace(Regex("[\\s<>»«›‹→←＞＜]+"),"")
    private fun navigation(title:String)=compact(title).let {it.isBlank() || Regex("^(?:上[一]?|下[一]?|前|后)[页章]$|^(?:返回|查看|全部|所有|完整|章节|小说)*(?:目录|章节)$|^(?:首页|末页|尾页|书架|登录|加入书签)$").matches(it)}
    fun directoryUrl(html: String, source: String): String? = WebHtml.links(html,source)
        .firstOrNull { Regex("^(?:返回|查看|全部|所有|完整|章节|小说)*(?:目录|章节)$").matches(compact(it.title)) }?.url
    fun nextDirectoryPage(html: String, source: String): String? = WebHtml.links(html,source)
        .firstOrNull { (compact(it.title) in listOf("下一页","下页","后页") || it.attributes["rel"]?.equals("next",true)==true && !it.title.contains("章")) && it.url!=source.substringBefore('#') }?.url
    fun directoryPages(html:String,source:String):List<String> {
        val links=WebHtml.links(html,source).filter {
            val label=compact(it.title)
            val directoryLabel=Regex("^(?:返回|查看|全部|所有|完整|章节|小说)*(?:目录|章节)$").matches(label)
            directoryLabel || directoryAddress(it.url) && (navigation(it.title) || Regex("(?:第)?[0-9]+(?:页)?").matches(label) || Regex("(?:第)?[0-9]+[章页]?[-–—~至][第]?[0-9]+[章页]?").matches(label))
        }.map {it.url}
        val options=Regex("(?is)<option\\b([^>]*)>").findAll(html).mapNotNull {m->
            val attrs=WebHtml.attributes(m.groupValues[1]);val value=attrs["value"] ?: attrs["data-value"] ?: return@mapNotNull null
            if(value.matches(Regex("[0-9]+"))) {
                val pattern=Regex("((?:index|list|catalog|directory)[_-])([0-9]+)(\\.html?)",RegexOption.IGNORE_CASE)
                val url=pattern.replace(source){it.groupValues[1]+value+it.groupValues[3]}
                if(url!=source)url else null
            } else WebHtml.resolve(source,value)?.takeIf {directoryAddress(it)}
        }.toList()
        return (listOfNotNull(nextDirectoryPage(html,source))+links+options).distinct().filter {it!=source.substringBefore('#')}
    }
    fun chapters(html: String, source: String): List<WebCatalogEntry> {
        val clean=html.replace(Regex("(?is)<(script|style|nav|header|footer|aside)\\b[^>]*>.*?</\\1>"),"")
        val lists=WebHtml.containers(clean).filter {(attrs,_)->
            val a=WebHtml.attributes(attrs);val name=a["id"].orEmpty()+" "+a["class"].orEmpty()
            Regex("(?:^|[\\s_-])(?:list|listmain|chapterlist|chapter-list|chapters|catalog|directory|mulu)(?:$|[\\s_-])",RegexOption.IGNORE_CASE).containsMatchIn(name) && !Regex("latest|recommend|related|newest",RegexOption.IGNORE_CASE).containsMatchIn(name)
        }.map {(_,body)->WebHtml.links(body,source).filter {!navigation(it.title) && !directoryAddress(it.url)}}.filter {it.isNotEmpty()}
        val entries = (if(lists.isNotEmpty())lists.flatten() else WebHtml.links(clean,source).filter { isChapterTitle(it.title) })
            .filter {it.title.isNotBlank() && !navigation(it.title) && !directoryAddress(it.url)}
            .map {WebCatalogEntry(it.title,it.url)}
            .distinctBy { it.url }
        val numbered = entries.map { it to chapterNumber(it.title) }
        return numbered.sortedBy { (entry, number) -> number ?: if (entry.title.startsWith("序章") || entry.title.startsWith("楔子")) -1 else Int.MAX_VALUE }.map { it.first }
    }
    fun isChapterTitle(title:String)=Regex("^(?:正文\\s*)?(?:第.{1,16}[章回节卷]|序章|楔子|番外|尾声|Chapter\\s+\\d+)",RegexOption.IGNORE_CASE).containsMatchIn(title)
    fun order(entries:List<WebCatalogEntry>):List<WebCatalogEntry> = entries.distinctBy {it.url}.sortedBy {chapterNumber(it.title) ?: if(it.title.startsWith("序章") || it.title.startsWith("楔子"))-1 else Int.MAX_VALUE}
    fun startsAfterFirstChapter(entries:List<WebCatalogEntry>):Boolean = entries.mapNotNull {chapterNumber(it.title)}.minOrNull()?.let {it>1} ?: false
    private fun chapterNumber(title: String): Int? {
        if (title.startsWith("番外") || title.startsWith("尾声")) return null
        val value = Regex("第\\s*([0-9零〇一二两三四五六七八九十百千万]+)\\s*[章回节]").find(title)?.groupValues?.get(1) ?: return null
        value.toIntOrNull()?.let { return it }
        if(value.none {it in "十百千万"})return value.map {if(it=='两')2 else "零一二三四五六七八九".indexOf(if(it=='〇')'零' else it)}.joinToString("").toIntOrNull()
        var sum = 0; var digit = 0
        value.forEach { c ->
            val d = "零一二三四五六七八九".indexOf(c)
            if (d >= 0) digit = d else if (c == '两') digit = 2 else {
                val unit = when(c) { '十' -> 10; '百' -> 100; '千' -> 1000; '万' -> 10000; else -> 0 }
                if (unit == 10000) { sum = (sum + digit) * unit; digit = 0 }
                else { sum += (if (digit == 0) 1 else digit) * unit; digit = 0 }
            }
        }
        return sum + digit
    }
}
