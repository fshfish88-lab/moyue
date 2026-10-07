package com.moyue.reader.core.document

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Never extract entry names as paths. Read and validate actual bytes, then use numbered cache files. */
object ComicArchive {
    const val MAX_SOURCE = 200L * 1024 * 1024
    const val MAX_PAGE = 32L * 1024 * 1024
    const val MAX_TOTAL = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 2000
    val extensions = setOf("jpg", "jpeg", "png", "webp")

    fun scan(file: File, inspect: (ByteArray) -> ImageSize, checkCancelled: () -> Unit = {}): List<ImagePage> {
        require(file.length() <= MAX_SOURCE) { "漫画文件不能超过 200 MB" }
        val pages = mutableListOf<ImagePage>(); val paths = mutableSetOf<String>(); var total = 0L; var entries = 0
        ZipFile(file).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                checkCancelled()
                val entry = iterator.nextElement(); entries++
                require(entries <= MAX_ENTRIES) { "漫画归档最多包含 2000 个条目" }
                val name = safeName(entry.name, entry.isDirectory)
                require(paths.add(Normalizer.normalize(name.trimEnd('/'),Normalizer.Form.NFC).lowercase(Locale.ROOT))) { "归档包含重复路径" }
                if (entry.isDirectory) continue
                require(entry.method in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) { "不支持此归档压缩方式" }
                val lower = name.lowercase(Locale.ROOT)
                val ignored = name.split('/').any { it == "__MACOSX" || it.startsWith(".") } || lower.substringAfterLast('/') in setOf("thumbs.db", "desktop.ini")
                val image = lower.substringAfterLast('.', "") in extensions && !ignored
                require(image || ignored || lower.substringAfterLast('/') == "comicinfo.xml") { "请选择纯图片 ZIP / CBZ；其他文档包暂不支持" }
                val bytes = zip.getInputStream(entry).use { stream ->
                    readBounded(stream, minOf(MAX_PAGE, MAX_TOTAL-total), entry.crc, checkCancelled)
                }
                total += bytes.size
                require(total <= MAX_TOTAL) { "漫画实际解压内容超过 1 GB" }
                if (image) {
                    val size = inspect(bytes)
                    pages += ImagePage(name, size.width, size.height)
                }
            }
        }
        require(pages.isNotEmpty()) { "归档中没有支持的图片" }
        return pages.sortedWith { a,b -> NaturalPageOrder.compare(a.name,b.name) }
    }

    fun page(file: File, page: ImagePage): ByteArray = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry(page.name)) { "漫画页面不存在" }
        require(!entry.isDirectory && safeName(entry.name,false) == page.name)
        zip.getInputStream(entry).use { readBounded(it, MAX_PAGE, entry.crc) }
    }

    internal fun safeName(raw: String, directory: Boolean): String {
        require(raw.length in 1..1024 && !raw.startsWith('/') && '\\' !in raw && ':' !in raw && raw.none { it.code < 32 }) { "归档路径不安全" }
        val name = if (directory) raw.trimEnd('/') else raw
        require(name.split('/').none { it.isBlank() || it == "." || it == ".." }) { "归档路径越界" }
        return if (directory) "$name/" else name
    }

    internal fun readBounded(stream: InputStream, limit: Long, expectedCrc: Long = -1, checkCancelled: () -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(65536); val crc = CRC32(); var total = 0L
        while (true) {
            checkCancelled()
            val n = stream.read(buffer); if (n < 0) break
            total += n
            require(total <= limit) { "页面或归档实际解压内容超出限制" }
            crc.update(buffer,0,n); out.write(buffer,0,n)
        }
        require(expectedCrc < 0 || crc.value == expectedCrc) { "漫画归档校验失败，文件可能损坏" }
        return out.toByteArray()
    }
}
