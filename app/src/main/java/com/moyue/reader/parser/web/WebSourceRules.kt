package com.moyue.reader.parser.web
import java.net.URI
/** Explicit rules are small and local; unknown websites retain generic extraction. */
internal data class WebSourceRule(val catalogUrl: String, val chapterSelector: String?)
internal object WebSourceRules {
 fun forUrl(source: String): WebSourceRule? {
  val uri=runCatching {URI(source)}.getOrNull() ?: return null
  val path=uri.path.orEmpty()
  val host=uri.host?.lowercase() ?: return null
  val rule=when {
   host in setOf("www.00shu.la","www.00shu.info","00shu.la","00shu.info") -> Regex("^(/[0-9]+/[0-9]+/)").find(path)?.groupValues?.get(1)?.let {it to "#list dd a"}
   host in setOf("www.biqugex.org","biqugex.org") -> Regex("^(/biquge/[0-9]+/)").find(path)?.groupValues?.get(1)?.let {it to null}
   host == "tianyashuku.net" || host == "www.tianyashuku.net" -> Regex("^(/cn/[0-9]+/)").find(path)?.groupValues?.get(1)?.let {it to "#list a"}
   host == "www.lishirenwu.com" || host == "lishirenwu.com" -> Regex("^(/guji/[a-z]+/)").find(path)?.groupValues?.get(1)?.let {it to null}
   else -> null
  } ?: return null
  return WebSourceRule(URI(uri.scheme,null,uri.host,uri.port,rule.first,null,null).toString(),rule.second)
 }
}
