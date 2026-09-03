package com.moyue.reader.parser.epub

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class UnsafeArchiveException(message: String) : IOException(message)

class SafeEpubExtractor(
    private val maxEntries: Int = 10_000,
    private val maxEntryBytes: Long = 50L * 1024 * 1024,
    private val maxTotalBytes: Long = 200L * 1024 * 1024,
) {
    fun extract(epub: File, destination: File) {
        if (destination.exists()) destination.deleteRecursively()
        if (!destination.mkdirs()) throw IOException("无法创建 EPUB 缓存目录")
        val root = destination.canonicalFile
        var total = 0L
        try {
            ZipFile(epub).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > maxEntries) throw UnsafeArchiveException("EPUB 文件条目过多")
                entries.forEach { entry ->
                    val output = File(root, entry.name).canonicalFile
                    if (output != root && !output.path.startsWith(root.path + File.separator)) {
                        throw UnsafeArchiveException("EPUB 包含不安全路径")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            output.outputStream().buffered().use { sink ->
                                val buffer = ByteArray(16 * 1024)
                                var entryBytes = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    total += read
                                    if (entryBytes > maxEntryBytes || total > maxTotalBytes) {
                                        throw UnsafeArchiveException("EPUB 解压大小超过安全限制")
                                    }
                                    sink.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }
}
