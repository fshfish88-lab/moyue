package com.moyue.reader.parser.web
import java.net.URI
/** Contains catalog metadata only, never HTML, request headers or cookies. */
class WebCatalogTrace {
 private val lines=mutableListOf<String>()
 fun record(event:String,url:String?,detail:String="") {
  if(lines.size>=1500)return
  lines += listOf(event,url?.let(::safeUrl).orEmpty(),detail.replace('\n',' ').replace('\r',' ').take(500)).joinToString("\t")
 }
 suspend fun fetch(source:WebPageSource,url:String,render:Boolean=false):FetchedWebPage {
  record(if(render)"RENDER_REQUEST" else "REQUEST",url)
  return try {
   (if(render)source.render(url) else source.fetch(url)).also {record("RESPONSE",it.finalUrl,"transport=${it.transport} status=${if(it.statusCode>0)it.statusCode.toString() else "unknown"} htmlChars=${it.html.length}")}
  } catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(e:Exception){record("ERROR",url,e.javaClass.simpleName+" "+Regex("HTTP [0-9]{3}").find(e.message.orEmpty())?.value.orEmpty());throw e}
 }
 fun text():String = "墨阅网页目录抓取诊断\n不包含Cookie、请求头或网页正文。\n"+lines.joinToString("\n")+"\n"
 private fun safeUrl(value:String):String = runCatching {
  val u=URI(value)
  val query=u.rawQuery?.split('&')?.joinToString("&") {part->val key=part.substringBefore('=');if(key.lowercase() in setOf("page","p","index"))part else "$key=[redacted]"}
  u.scheme+"://"+u.host+(if(u.port>=0)":"+u.port else "")+u.rawPath+(query?.let {"?$it"} ?: "")
 }.getOrDefault("[invalid URL]")
}
