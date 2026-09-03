package com.moyue.reader.core.storage

import java.io.File

class BookStorage(
    private val filesDir: File,
    private val cacheDir: File,
) {
    fun bookDirectory(bookId: Long): File {
        require(bookId > 0) { "bookId must be positive" }
        return File(filesDir, "books/$bookId")
    }

    fun sourceFile(bookId: Long, extension: String): File {
        val safeExtension = extension.lowercase().removePrefix(".")
        require(safeExtension in setOf("txt", "epub", "html")) { "unsupported source extension" }
        return File(bookDirectory(bookId), "source.$safeExtension")
    }

    fun coverFile(bookId: Long): File = File(bookDirectory(bookId), "cover.png")

    fun epubCache(bookId: Long): File {
        require(bookId > 0) { "bookId must be positive" }
        return File(cacheDir, "books/$bookId/epub")
    }

    fun webCache(bookId: Long): File {
        require(bookId > 0) { "bookId must be positive" }
        return File(cacheDir, "books/$bookId/web")
    }

    fun importTemp(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "unsafe taskId" }
        return File(cacheDir, "import_tmp/$taskId")
    }

    fun ensureRoots() {
        File(filesDir, "books").mkdirs()
        File(cacheDir, "books").mkdirs()
        File(cacheDir, "import_tmp").mkdirs()
    }
}
