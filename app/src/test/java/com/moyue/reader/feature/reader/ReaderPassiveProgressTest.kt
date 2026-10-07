package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ReaderPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the passive position updates that [ReaderViewModel.updatePosition] schedules.
 *
 * That method updates the in-memory state first and only then launches the delayed save on
 * `viewModelScope`, so these cases drive the state with [passiveUpdate] and then persist it through
 * [ReaderViewModel.flushProgress]. The launched save itself cannot run here: `viewModelScope` needs
 * `Dispatchers.Main`, and `kotlinx-coroutines-test` cannot be resolved in this offline build (the
 * cached 1.10.2 metadata demands 1.10.2 core artifacts that are not cached). The real delayed save is
 * therefore covered on a device.
 */
class ReaderPassiveProgressTest {
    private fun passiveUpdate(viewModel: ReaderViewModel, position: ReaderPosition, chapterProgress: Float) {
        try {
            viewModel.updatePosition(position, chapterProgress)
        } catch (_: IllegalStateException) {
            // Expected: the delayed save cannot be launched without Dispatchers.Main. The state update
            // this method performs before launching it already happened.
        }
    }

    @Test
    fun scrollingForwardKeepsTheRealPosition() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)
        passiveUpdate(viewModel, ReaderPosition(5, 11), .6f)
        viewModel.flushProgress()

        assertEquals(5, source.storedProgress?.blockIndex)
        assertEquals(11, source.storedProgress?.charOffset)
        assertEquals(.6f, source.storedProgress?.chapterProgress)
        assertEquals(.52f, source.storedProgress?.bookProgress)
    }

    @Test
    fun scrollingBackInsideTheRestoredChapterSavesTheRealPosition() = runBlocking {
        // A chapter the reader actually reached is a real position: moving backwards inside it must
        // be persisted, not silently dropped as if the restore had failed. Note the stored book
        // progress goes DOWN (.5 -> .44), which the previous absolute-progress guard refused.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)
        passiveUpdate(viewModel, ReaderPosition(0, 0), .2f)
        viewModel.flushProgress()

        assertEquals(0, source.storedProgress?.blockIndex)
        assertEquals(0, source.storedProgress?.charOffset)
        assertEquals(.2f, source.storedProgress?.chapterProgress)
        assertEquals(.44f, source.storedProgress?.bookProgress)
    }

    @Test
    fun passiveUpdatesInAnEarlierChapterCannotOverwriteTheStoredPosition() = runBlocking {
        // Opening the cover of a book stored at chapter 2 (index 2) must not replace the stored
        // position through passive saves; that is the data loss this guard exists for.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 0)
        passiveUpdate(viewModel, ReaderPosition(1, 1), .9f)
        viewModel.flushProgress()

        assertEquals(3L, source.storedProgress?.chapterId)
        assertEquals(7, source.storedProgress?.charOffset)
        assertEquals(.5f, source.storedProgress?.bookProgress)
    }
}
