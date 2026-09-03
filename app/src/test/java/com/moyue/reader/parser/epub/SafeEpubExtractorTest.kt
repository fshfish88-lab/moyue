package com.moyue.reader.parser.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeEpubExtractorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun zipSlipEntryIsRejectedWithoutWritingOutsideDestination() {
        val epub = temporaryFolder.newFile("bad.epub")
        ZipOutputStream(FileOutputStream(epub)).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val destination = temporaryFolder.newFolder("unpacked")
        val outside = requireNotNull(destination.parentFile).resolve("escape.txt")

        val result = runCatching { SafeEpubExtractor().extract(epub, destination) }

        assertTrue(result.exceptionOrNull() is UnsafeArchiveException)
        assertFalse(outside.exists())
    }
}
