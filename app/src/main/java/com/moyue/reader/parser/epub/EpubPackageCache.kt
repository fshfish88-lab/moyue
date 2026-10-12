package com.moyue.reader.parser.epub

import java.io.File

/** One active book per reader session. Source and metadata changes invalidate the package. */
internal class EpubPackageCache(private val parser: EpubPackageParser = EpubPackageParser()) {
    private data class Stamp(val path: String, val exists: Boolean, val size: Long, val modified: Long)
    private fun stamp(file: File) = Stamp(file.canonicalPath, file.exists(), file.length(), file.lastModified())
    private var files = emptyList<File>()
    private var stamps = emptyList<Stamp>()
    private var cached: EpubPackage? = null

    @Synchronized
    fun get(source: File, root: File): EpubPackage {
        val current = listOf(source, root) + files
        val snapshot = current.map(::stamp)
        cached?.takeIf { snapshot == stamps }?.let { return it }
        val parsed = parser.parse(root)
        files = parsed.metadataFiles
        stamps = (listOf(source, root) + files).map(::stamp)
        cached = parsed
        return parsed
    }

    @Synchronized
    fun clear() {
        cached = null
        files = emptyList()
        stamps = emptyList()
    }
}
