package com.moyue.reader.parser.web

import java.net.URI

internal object WebHtml {
    data class Link(val title:String,val url:String,val attributes:Map<String,String>)
    fun attributes(text:String):Map<String,String> = Regex("(?is)([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").findAll(text).associate {m->m.groupValues[1].lowercase() to entities(m.groupValues.drop(2).firstOrNull {it.isNotEmpty()}.orEmpty())}
    fun entities(text:String):String = text.replace(Regex("(?i)&#(x[0-9a-f]+|[0-9]+);?")) {m->
        val v=m.groupValues[1];val code=if(v.startsWith('x',true))v.drop(1).toIntOrNull(16) else v.toIntOrNull()
        if(code!=null && Character.isValidCodePoint(code) && code !in 0xd800..0xdfff)String(Character.toChars(code)) else m.value
    }.replace("&nbsp;"," ",true).replace("&amp;","&",true).replace("&lt;","<",true).replace("&gt;",">",true).replace("&quot;","\"",true).replace("&#39;","'",true).replace("&raquo;","»",true).replace("&laquo;","«",true)
    fun text(html:String)=entities(html.replace(Regex("(?is)<[^>]+>"),"")).replace(Regex("\\s+")," ").trim()
    fun resolve(source:String,href:String):String? {
        if(href.isBlank() || href.startsWith('#'))return null
        val base=runCatching {URI(source)}.getOrNull() ?: return null
        val url=runCatching {base.resolve(entities(href).trim()).normalize()}.getOrNull() ?: return null
        if(url.scheme?.lowercase() !in listOf("http","https") || !url.host.equals(base.host,true) || url.port!=base.port)return null
        return url.toString().substringBefore('#')
    }
    fun links(html:String,source:String):List<Link> = Regex("(?is)<a\\b([^>]*)>(.*?)</a>").findAll(html).mapNotNull {m->
        val attrs=attributes(m.groupValues[1]);val url=resolve(source,attrs["href"] ?: return@mapNotNull null) ?: return@mapNotNull null
        Link(text(m.groupValues[2]).ifBlank {attrs["title"].orEmpty()},url,attrs)
    }.toList()
    fun containers(html:String):List<Pair<String,String>> {
        data class Open(val tag:String,val attrs:String,val start:Int)
        val stack=mutableListOf<Open>();val result=mutableListOf<Pair<String,String>>()
        Regex("(?is)<(/?)(div|section|dl|ul|ol)\\b([^>]*)>").findAll(html).forEach {m->
            val tag=m.groupValues[2].lowercase()
            if(m.groupValues[1].isEmpty())stack+=Open(tag,m.groupValues[3],m.range.last+1)
            else {
                val i=stack.indexOfLast {it.tag==tag}
                if(i>=0){val open=stack[i];result+=open.attrs to html.substring(open.start,m.range.first);while(stack.size>i)stack.removeAt(stack.lastIndex)}
            }
        }
        return result
    }
}
