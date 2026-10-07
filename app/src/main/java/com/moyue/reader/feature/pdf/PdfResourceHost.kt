package com.moyue.reader.feature.pdf

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

internal const val PDF_ORIGIN = "https://appassets.androidplatform.net"
internal const val PDF_SOURCE = "$PDF_ORIGIN/pdf-source/document.pdf"

/** A host sees only one chosen application-owned document, never arbitrary device paths. */
internal class PdfResourceHost(private val context: Context, private val source: File) {
    fun response(request: WebResourceRequest): WebResourceResponse = response(request.url, request.requestHeaders)
    fun response(uri: Uri, headers: Map<String, String> = emptyMap()): WebResourceResponse {
        if(com.moyue.reader.BuildConfig.DEBUG) android.util.Log.d("MoyuePdfHost", "resource ${uri.path} ${headers.entries.firstOrNull {it.key.equals("Range",true)}?.value.orEmpty()}")
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return denied()
        val path = uri.path.orEmpty()
        if (path == "/pdf-source/document.pdf") {
            val size = source.length()
            val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
            val matched = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = matched?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = matched?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (size <= 0 || start !in 0 until size || end < start) return denied()
            val input = source.inputStream(); input.channel.position(start)
            val count = end - start + 1
            val stream = object : FilterInputStream(input) {
                var remaining = count
                override fun available(): Int = minOf(`in`.available().toLong(),remaining).coerceAtLeast(0).toInt()
                override fun read(): Int { if (remaining <= 0) return -1; val n = super.read(); if(n >= 0) remaining--; return n }
                override fun read(b: ByteArray, off: Int, len: Int): Int { if(remaining <= 0) return -1; val n = `in`.read(b, off, minOf(len.toLong(), remaining).toInt()); if(n > 0) remaining -= n; return n }
            }
            // WebView derives Content-Length from available(); providing it again duplicates the
            // header. A range stream must also report the bounded length, not the remaining file.
            val responseHeaders = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
            if(matched != null) responseHeaders["Content-Range"] = "bytes $start-$end/$size"
            return WebResourceResponse("application/pdf", null, if(matched == null) 200 else 206, "OK", responseHeaders, stream)
        }
        if (!path.startsWith("/assets/pdf/") && !path.startsWith("/assets/pdfjs/")) return denied()
        if (path.split('/').any { it == ".." || it == "." } || path.contains('\\')) return denied()
        return try {
            val assetPath = path.removePrefix("/assets/")
            val mime = when(assetPath.substringAfterLast('.')) {
                "mjs", "js" -> "application/javascript"
                "html" -> "text/html"
                "css" -> "text/css"
                "json" -> "application/json"
                "wasm" -> "application/wasm"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "ftl" -> "text/plain"
                else -> "application/octet-stream"
            }
            val stream: InputStream = if(assetPath == "pdfjs/web/viewer.html") {
                // Keep the upstream file byte-identical; add our adapter only in the served response.
                val html = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                    .replace("</head>", "<link rel=\"stylesheet\" href=\"../../pdf/moyue-pdf.css\"><script src=\"../../pdf/moyue-pdf.js\"></script></head>")
                ByteArrayInputStream(html.toByteArray())
            } else context.assets.open(assetPath)
            WebResourceResponse(mime, "UTF-8", stream)
        } catch (error: Exception) { if(com.moyue.reader.BuildConfig.DEBUG) android.util.Log.w("MoyuePdfHost", "asset unavailable: $path",error); denied() }
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
