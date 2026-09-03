package com.moyue.reader.core.storage

import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.ParsedChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportStage
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicImportCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulImportPublishesOnlyAfterValidationAndDeletesTemp() = runBlocking {
        val fixture = fixture()
        val seenStages = mutableListOf<ImportStage>()

        val result = fixture.coordinator.import(
            request = fixture.request,
            processor = FakeProcessor(),
            onProgress = { seenStages += it.stage },
        )

        assertEquals(ImportState.Completed(bookId = 42), result)
        assertEquals(
            listOf(
                ImportStage.WAITING,
                ImportStage.COPYING,
                ImportStage.DETECTING,
                ImportStage.PARSING,
                ImportStage.SAVING,
                ImportStage.COMPLETED,
            ),
            seenStages,
        )
        assertEquals(1, fixture.store.committedBooks)
        assertFalse(fixture.storage.importTemp(fixture.request.taskId).exists())
    }

    @Test
    fun cancellationDeletesTempAndCreatesNoBook() = runBlocking {
        val fixture = fixture()

        val result = fixture.coordinator.import(
            request = fixture.request,
            processor = FakeProcessor(cancelAt = ImportStage.PARSING),
        )

        assertTrue(result is ImportState.Cancelled)
        assertFalse(fixture.storage.importTemp(fixture.request.taskId).exists())
        assertEquals(0, fixture.store.committedBooks)
    }

    @Test
    fun recoverableFailureDeletesTempAndCreatesNoBook() = runBlocking {
        val fixture = fixture()

        val result = fixture.coordinator.import(
            request = fixture.request,
            processor = FakeProcessor(recoverableAt = ImportStage.DETECTING),
        )

        assertTrue(result is ImportState.RecoverableError)
        assertFalse(fixture.storage.importTemp(fixture.request.taskId).exists())
        assertEquals(0, fixture.store.committedBooks)
    }

    @Test
    fun startupCleanupRemovesOnlyUncommittedImports() {
        val fixture = fixture()
        val abandoned = fixture.storage.importTemp("abandoned").apply {
            mkdirs()
            resolve("source.txt").writeText("partial")
        }
        val bookDirectory = fixture.storage.bookDirectory(7).apply {
            mkdirs()
            resolve("source.txt").writeText("published")
        }

        fixture.coordinator.cleanupAbandonedImports()

        assertFalse(abandoned.exists())
        assertTrue(bookDirectory.exists())
    }

    private fun fixture(): Fixture {
        val files = temporaryFolder.newFolder("files")
        val cache = temporaryFolder.newFolder("cache")
        val source = temporaryFolder.newFile("story.txt").apply { writeText("第一章 开始") }
        val storage = BookStorage(files, cache).also(BookStorage::ensureRoots)
        val store = FakeStore()
        return Fixture(
            storage = storage,
            store = store,
            request = ImportRequest("task-1", source, SourceType.TXT, "txt"),
            coordinator = AtomicImportCoordinator(storage, store),
        )
    }

    private data class Fixture(
        val storage: BookStorage,
        val store: FakeStore,
        val request: ImportRequest,
        val coordinator: AtomicImportCoordinator,
    )

    private class FakeStore : AtomicImportStore {
        var committedBooks = 0

        override suspend fun commit(prepared: PreparedImport): Long {
            check(prepared.stagedSource.exists())
            committedBooks += 1
            return 42
        }
    }

    private class FakeProcessor(
        private val cancelAt: ImportStage? = null,
        private val recoverableAt: ImportStage? = null,
    ) : ImportProcessor {
        override suspend fun detect(source: File) {
            failAt(ImportStage.DETECTING)
        }

        override suspend fun parse(source: File, sourceType: SourceType): ParsedBook {
            failAt(ImportStage.PARSING)
            return ParsedBook(
                title = "测试书",
                author = null,
                sourceType = sourceType,
                chapters = listOf(
                    ParsedChapter("第一章", 0, 0, source.length(), null),
                ),
            )
        }

        override suspend fun validate(book: ParsedBook) {
            failAt(ImportStage.SAVING)
        }

        private fun failAt(stage: ImportStage) {
            if (cancelAt == stage) throw ImportCancelledException()
            if (recoverableAt == stage) throw RecoverableImportException("可重新选择编码")
        }
    }
}
