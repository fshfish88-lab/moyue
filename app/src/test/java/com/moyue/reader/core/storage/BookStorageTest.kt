package com.moyue.reader.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookStorageTest {
    private val root = File("build/test-storage")
    private val storage = BookStorage(File(root, "files"), File(root, "cache"))

    @Test
    fun epubSourceAndDerivedCacheAreSeparated() {
        assertTrue(storage.sourceFile(7, "epub").invariantSeparatorsPath.endsWith("files/books/7/source.epub"))
        assertTrue(storage.epubCache(7).invariantSeparatorsPath.endsWith("cache/books/7/epub"))
    }

    @Test
    fun taskDirectoryCannotEscapeImportRoot() {
        assertEquals("task-safe_123", storage.importTemp("task-safe_123").name)
        val result = runCatching { storage.importTemp("../escape") }
        assertTrue(result.isFailure)
    }
}
