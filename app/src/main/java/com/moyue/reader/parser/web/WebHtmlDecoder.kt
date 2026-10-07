package com.moyue.reader.parser.web

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decode the original response once. Never persist silently inserted replacement characters. */
object WebHtmlDecoder {
    fun decode(bytes:ByteArray,contentType:String):String {
        val bom=when {
            bytes.size>=3 && bytes[0]==0xef.toByte() && bytes[1]==0xbb.toByte() && bytes[2]==0xbf.toByte()->"UTF-8" to 3
            bytes.size>=2 && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()->"UTF-16LE" to 2
            bytes.size>=2 && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte()->"UTF-16BE" to 2
            else->null
        }
        val header=Regex("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)").find(contentType)?.groupValues?.get(1)
        val head=String(bytes,0,minOf(bytes.size,16384),Charsets.ISO_8859_1)
            .replace(Regex("(?s)<!--.*?-->"),"").replace(Regex("(?is)<script\\b[^>]*>.*?</script>"),"")
        val meta=Regex("(?is)<meta\\b[^>]*>").findAll(head).mapNotNull {
            Regex("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)").find(it.value)?.groupValues?.get(1)
        }.firstOrNull()
        val declared=listOfNotNull(header,meta)
        val candidates=if(bom!=null)listOf(bom.first) else declared+listOf("UTF-8","GB18030")
        for(name in candidates.distinct()) {
            val charset=runCatching {Charset.forName(if(name.equals("gb2312",true) || name.equals("gbk",true))"GB18030" else name)}.getOrNull() ?: continue
            val value=runCatching {charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,bom?.second ?: 0,bytes.size-(bom?.second ?: 0))).toString()}.getOrNull()
            if(value!=null)return value
        }
        throw IOException("无法识别网页编码，请在网页浏览中确认页面正常后重试")
    }
}
