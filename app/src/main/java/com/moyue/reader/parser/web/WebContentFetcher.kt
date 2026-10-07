package com.moyue.reader.parser.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

data class FetchedWebPage(val finalUrl: String, val html: String)

class WebContentFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxBodyBytes: Int = 5 * 1024 * 1024,
    private val openConnection:(String)->HttpURLConnection = {URL(it).openConnection() as HttpURLConnection},
) {
    suspend fun fetch(url: String): FetchedWebPage = withContext(Dispatchers.IO) {
        validate(url)
        val connection = openConnection(url)
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", "Moyue/1.0 Android Reader")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("网页请求失败（HTTP $status）")
            val finalUrl = connection.url.toString().also(::validate)
            val type = connection.contentType.orEmpty()
            if (!type.contains("text/html", true) && !type.contains("application/xhtml", true)) {
                throw IOException("该地址不是网页内容")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > maxBodyBytes) throw IOException("网页内容超过 5MB 限制")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            FetchedWebPage(finalUrl, WebHtmlDecoder.decode(bytes,type))
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "仅支持 HTTP/HTTPS 地址" }
        require(!uri.host.isNullOrBlank()) { "网页地址缺少域名" }
    }
}
