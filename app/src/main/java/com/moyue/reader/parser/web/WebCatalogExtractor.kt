package com.moyue.reader.parser.web
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class WebCatalogEntry(val title: String, val url: String)
class WebCatalogExtractor {
 private fun directoryAddress(url:String)=Regex("(?:index|list|catalog|directory)(?:[_./?=-]|\\.html?$|$)|[?&](?:page|p)=",RegexOption.IGNORE_CASE).containsMatchIn(url)
 private fun compact(text:String)=text.replace(Regex("[\\s<>»«›‹→←＞＜]+"),"")
 private fun directoryLabel(text:String)=compact(text).let {it.endsWith("目录") || Regex("^(?:返回|查看|全部|所有|完整|章节|小说)*(?:目录|章节)$").matches(it)}
 private fun navigation(title:String)=compact(title).let {it.isBlank() || directoryLabel(it) || Regex("^(?:上[一]?|下[一]?|前|后)[页章]$|^(?:首页|末页|尾页|书架|登录|加入书签)$").matches(it)}
 private fun prefix(source:String):String=URI(source).path.orEmpty().let {if(it.endsWith('/'))it else it.substringBeforeLast('/',"")+"/"}
 private fun sameBook(source:String,url:String)=URI(url).path.orEmpty().startsWith(prefix(source))
 fun directoryUrl(html:String,source:String):String? {
  val allLinks=WebHtml.links(html,source)
  val links=allLinks.filter {sameBook(source,it.url)}
  return links.firstOrNull {directoryLabel(it.title) && compact(it.title).contains("全部")}?.url
   ?: links.firstOrNull {directoryLabel(it.title)}?.url
   ?: links.firstOrNull {directoryAddress(it.url) && !isChapterTitle(it.title) && !Regex("页|^[0-9]+$").containsMatchIn(compact(it.title))}?.url
   ?: allLinks.firstOrNull {Regex("^(?:返回|查看|全部|所有|完整|章节|小说)*(?:目录|章节)$").matches(compact(it.title))}?.url
   ?: WebSourceRules.forUrl(source)?.catalogUrl?.takeIf {it!=source.substringBefore('#')}
 }
 fun nextDirectoryPage(html:String,source:String):String?=WebHtml.links(html,source).firstOrNull {
  (compact(it.title) in listOf("下一页","下页","后页") || it.attributes["rel"]?.equals("next",true)==true && !it.title.contains("章")) && it.url!=source.substringBefore('#') && sameBook(source,it.url)
 }?.url
 fun directoryPages(html:String,source:String):List<String> {
  val links=WebHtml.links(html,source).filter {
   val label=compact(it.title)
   sameBook(source,it.url) && (directoryLabel(label) || directoryAddress(it.url) && (navigation(label) || Regex("(?:第)?[0-9]+(?:页)?|(?:第)?[0-9]+[章页]?[-–—~至][第]?[0-9]+[章页]?").matches(label)))
  }.map {it.url}
  val doc=Jsoup.parse(html,source)
  val options=doc.select("select option[value], select option[data-value]").mapNotNull {option->
   val value=option.attr("value").ifBlank {option.attr("data-value")}
   if(value.matches(Regex("[0-9]+"))) {
    val pattern=Regex("((?:index|list|catalog|directory)[_-])([0-9]+)(\\.html?)",RegexOption.IGNORE_CASE)
    pattern.replace(source){it.groupValues[1]+value+it.groupValues[3]}.takeIf {it!=source}
   } else WebHtml.resolve(source,value)?.takeIf {sameBook(source,it) && (directoryAddress(it) || Regex("[页章回]|^[0-9]+[-–—~至][0-9]+$").containsMatchIn(option.text()))}
  }
  return (listOfNotNull(nextDirectoryPage(html,source))+links+options).distinct().filter {it!=source.substringBefore('#')}
 }
 fun chapters(html:String,source:String):List<WebCatalogEntry> {
  val doc=Jsoup.parse(html,source)
  doc.select("script,style,nav,header,footer,aside,.latest,#latest,.recommend,#recommend,.related,.newest").remove()
  fun entries(elements:Iterable<Element>)=elements.mapNotNull {a->
   val title=a.text().ifBlank {a.attr("title")}
   val url=WebHtml.resolve(source,a.attr("href")) ?: return@mapNotNull null
   if(title.isBlank() || navigation(title) || directoryAddress(url) || !sameBook(source,url))null else WebCatalogEntry(title,url)
  }.distinctBy {it.url}
  val rule=WebSourceRules.forUrl(source)
  val explicit=rule?.chapterSelector?.let {entries(doc.select(it))}.orEmpty()
  if(explicit.isNotEmpty())return order(explicit)
  val containers=doc.select("#list,.listmain,#chapterlist,.chapterlist,.chapter-list,.chapters,#catalog,.catalog,#directory,.directory,#mulu,.mulu,#all-chapters")
   .filter {element->
    val heading=element.select("h1,h2,h3,dt").firstOrNull()?.text().orEmpty()
    !Regex("最新|最近更新|推荐").containsMatchIn(heading) || Regex("全部|完整|正文").containsMatchIn(element.select("h1,h2,h3,dt").joinToString {it.text()})
   }
  val strong=containers.flatMap {entries(it.select("a[href]"))}
  // Numbered same-book links recover complete lists whose containers use an unknown name.
  val fallback=entries(doc.select("a[href]")).filter {isChapterTitle(it.title)}
  return order(strong+fallback)
 }
 fun isDirectoryPage(html:String,source:String):Boolean {
  val entries=chapters(html,source)
  return entries.size>=3 && !startsAfterFirstChapter(entries) && entries.count {isChapterTitle(it.title)}>=3
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
