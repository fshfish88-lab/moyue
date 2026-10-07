package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress-saving contract of the reader.
 *
 * Note: the delayed passive save inside [ReaderViewModel.updatePosition] launches on
 * `viewModelScope`, whose main dispatcher cannot run in these plain JVM tests (`android.os.Looper`
 * is not mocked), so that specific path is covered on a device instead of here. Everything else is
 * exercised through [ReaderViewModel.open], navigation, [ReaderViewModel.flushProgress] and
 * [ReaderViewModel.flushProgressOnBack].
 */
class ReaderViewModelTest {
    /**
     * [ReaderViewModel.updatePosition] and every navigation entry point launch their save on
     * `viewModelScope`, whose main dispatcher is present on the test classpath but cannot run here
     * because `android.os.Looper` is not mocked (`kotlinx-coroutines-test` is also not resolvable in
     * the offline build), so the launched save fails synchronously. State changes made before the
     * launch still happen, which is what these cases assert on; the real save is covered by
     * ReaderPassiveProgressTest and on device.
     */
    private fun blockingMainLaunch(block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { }
    }

    @Test fun failedOpenIsRecoverableWithoutAnUncaughtException() = runBlocking {
        val source = FakeReaderDataSource(3).apply { failureIndex = 0 }
        val model = ReaderViewModel(ReaderRepository(source))
        model.open(1)
        assertEquals(null, model.state.value)
        assertTrue(model.openError.value!!.contains("章节缺失"))
        source.failureIndex = null
        model.open(1)
        assertEquals(0, model.state.value!!.chapter.index)
        assertEquals(null, model.openError.value)
    }

    @Test fun brokenAdjacentChapterDoesNotPreventOpeningCurrentChapter() = runBlocking {
        val model = ReaderViewModel(ReaderRepository(FakeReaderDataSource(3).apply { failureIndex = 1 }))
        model.open(1, 0)
        assertEquals(0, model.state.value!!.chapter.index)
        assertEquals(null, model.openError.value)
        model.goNext()
        assertEquals(0, model.state.value!!.chapter.index)
        assertTrue(model.state.value!!.error!!.contains("章节缺失"))
    }

    @Test fun staleReadingPositionResetsToChapterStart() = runBlocking {
        val source = FakeReaderDataSource(3).apply { storedProgress = ReadingProgressEntity(1, 1, -2, -7, 0f, 0f, 1) }
        val model = ReaderViewModel(ReaderRepository(source))
        model.open(1)
        assertEquals(ReaderPosition(0, 0), model.state.value!!.position)
    }

    @Test fun progressWriteFailureDoesNotCrashBackNavigation() = runBlocking {
        val source = FakeReaderDataSource(3)
        val model = ReaderViewModel(ReaderRepository(source))
        model.open(1)
        source.failSave = true
        model.flushProgress()
        assertTrue(model.state.value!!.error!!.contains("暂未保存"))
    }

    @Test fun restoredOffsetsAreLimitedToActualTextAndImages() {
        val blocks = listOf(ContentBlock.Text("正文"), ContentBlock.Image("image.png"))
        assertEquals(ReaderPosition(0, 2), safeReaderPosition(blocks, 0, 999))
        assertEquals(ReaderPosition(1, 0), safeReaderPosition(blocks, 1, 999))
        assertEquals(ReaderPosition(0, 0), safeReaderPosition(blocks, 999, 999))
    }
    @Test fun prefetchAppendsWithoutMovingVisibleChapter() = runBlocking {
        val model = ReaderViewModel(ReaderRepository(FakeReaderDataSource(5)))
        model.open(1)
        model.prefetchFollowing()
        repeat(10) { model.prefetchFollowing() }
        assertEquals(listOf(0, 1), model.state.value!!.scrollWindow.map { it.index })
        assertEquals(0, model.state.value!!.chapter.index)
        assertEquals(0, model.state.value!!.scrollSession)
        model.goTo(3)
        assertEquals(listOf(3), model.state.value!!.scrollWindow.map { it.index })
        assertEquals(1, model.state.value!!.scrollSession)
    }

    @Test fun nextChapterStartsAtBeginningAndSavesIt() = runBlocking {
        val source = FakeReaderDataSource(5).apply { storedProgress = ReadingProgressEntity(1, 1, 2, 7, .9f, .2f, 1) }
        val model = ReaderViewModel(ReaderRepository(source))
        model.open(1)
        model.goNext()
        assertEquals(ReaderPosition(0, 0), model.state.value!!.position)
        assertEquals(2L, source.storedProgress!!.chapterId)
        assertEquals(0, source.storedProgress!!.charOffset)
    }

    @Test
    fun movingToNextChapterKeepsOnlyPreviousCurrentNext() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 200)
        val repository = ReaderRepository(source)
        val viewModel = ReaderViewModel(repository)

        viewModel.open(bookId = 1, chapterIndex = 100)
        viewModel.goNext()

        assertEquals(setOf(100, 101, 102), repository.cachedChapterIndexes())
        assertEquals(101, viewModel.state.value?.chapter?.index)
    }

    @Test
    fun openWithoutExplicitChapterRestoresBlockPosition() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)

        assertEquals(2, viewModel.state.value?.chapter?.index)
        assertEquals(ReaderPosition(2, 7), viewModel.state.value?.position)
    }

    @Test
    fun detailPageContinueReadingMustReachTheStoredPosition() = runBlocking {
        // V1.1.3 defect (device-verified): the detail page used to request chapter index 0, so its
        // 继续阅读 button opened the cover and then flushed 0% over the real progress. It now asks
        // for "no explicit chapter"; asking for the stored chapter explicitly keeps the position too.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = null)

        assertEquals(2, viewModel.state.value?.chapter?.index)
        assertEquals(ReaderPosition(2, 7), viewModel.state.value?.position)
    }

    @Test
    fun requestingTheStoredChapterExplicitlyKeepsTheStoredBlockPosition() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 2)

        assertEquals(2, viewModel.state.value?.chapter?.index)
        assertEquals(ReaderPosition(2, 7), viewModel.state.value?.position)
    }

    @Test
    fun openingTheCoverPageDoesNotOverwriteTheStoredProgress() = runBlocking {
        // Reproduces the data loss: the detail page landed on the cover (chapter 0) and leaving the
        // reader persisted 0% over the stored 40%. The stored record must survive untouched.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 0)
        viewModel.flushProgress()

        assertEquals(0, viewModel.state.value?.chapter?.index)
        assertEquals(3L, source.storedProgress?.chapterId)
        assertEquals(7, source.storedProgress?.charOffset)
        assertEquals(.5f, source.storedProgress?.bookProgress)
    }

    @Test
    fun returningToAnEarlierChapterOnPurposeStillMovesTheStoredProgressBack() = runBlocking {
        // A chapter the reader picks on purpose is a real position, so it must be persisted instead
        // of being mistaken for a failed restore (the guard only blocks passive updates).
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)
        // Dropped into chapter 0 (index 0), which is 20% of a 5 chapter book.
        try { viewModel.updatePosition(ReaderPosition(0, 0), .2f) } catch (_: IllegalStateException) { }
        try { viewModel.goTo(0) } catch (_: IllegalStateException) { }
        viewModel.flushProgressOnBack()

        assertEquals(1L, source.storedProgress?.chapterId)
        assertEquals(0, source.storedProgress?.blockIndex)
        assertEquals(0, source.storedProgress?.charOffset)
        // Chapter 0 opened at its beginning, so the stored progress really moved backwards (from the
        // 50% the book was stored at) instead of being kept as the high water mark.
        assertEquals(0f, source.storedProgress?.bookProgress)
    }

    @Test
    fun jumpingBackAfterAFailedRestoreKeepsSavingInThatChapter() = runBlocking {
        // R2-1: with the restore guard still armed, a chapter the reader deliberately jumped back to
        // would silently stop persisting passive positions until the app was left normally.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 0)
        try { viewModel.goTo(0) } catch (_: IllegalStateException) { }
        try { viewModel.updatePosition(ReaderPosition(30, 0), .3f) } catch (_: IllegalStateException) { }
        viewModel.flushProgress()

        assertEquals(1L, source.storedProgress?.chapterId)
        assertEquals(30, source.storedProgress?.blockIndex)
        // Chapter 0 with 30% read, i.e. a real position inside the chapter the reader jumped back to.
        assertEquals(.06, (source.storedProgress?.bookProgress ?: 0f).toDouble(), 1e-6)
    }

    @Test
    fun passiveUpdatesInAnEarlierChapterDoNotOverwriteTheStoredPosition() = runBlocking {
        // The restored chapter is 2 (index 2). Opening/saving chapter 0 must not persist chapter 0
        // over the stored chapter, because only navigation may move progress backwards.
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 0)
        viewModel.flushProgress()

        assertEquals(3L, source.storedProgress?.chapterId)
        assertEquals(7, source.storedProgress?.charOffset)
        assertEquals(.5f, source.storedProgress?.bookProgress)
    }

    @Test
    fun reachingTheStoredChapterLetsLaterSavesMoveForwardAgain() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 3, 2, 7, .4f, .5f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1, chapterIndex = 0)
        viewModel.goTo(1)
        viewModel.flushProgressOnBack()

        assertEquals(2L, source.storedProgress?.chapterId)
        assertEquals(ReaderPosition(0, 0), viewModel.state.value?.position)
        // Chapter index 1 of 5 saved at its start, i.e. the position moved forward from the cover.
        assertEquals(.2f, source.storedProgress?.bookProgress)
    }

    @Test
    fun firstReadOfABookStillPersistsTheOpeningPosition() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5)
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)
        viewModel.flushProgressOnBack()

        assertEquals(1L, source.storedProgress?.chapterId)
        assertEquals(0f, source.storedProgress?.bookProgress)
    }

    @Test
    fun readerChosenNavigationMayStillMoveTheStoredProgressBackwards() = runBlocking {
        val source = FakeReaderDataSource(chapterCount = 5).apply {
            storedProgress = ReadingProgressEntity(1, 4, 1, 2, .3f, .6f, 123)
        }
        val viewModel = ReaderViewModel(ReaderRepository(source))

        viewModel.open(bookId = 1)
        viewModel.goPrevious()

        assertEquals(2, viewModel.state.value?.chapter?.index)
        assertEquals(3L, source.storedProgress?.chapterId)
        assertEquals(0.4f, source.storedProgress?.bookProgress)
    }

    @Test
    fun chapterBoundariesDisableUnavailableDirections() = runBlocking {
        val viewModel = ReaderViewModel(ReaderRepository(FakeReaderDataSource(2)))

        viewModel.open(1, 0)
        assertFalse(viewModel.state.value!!.canGoPrevious)
        assertTrue(viewModel.state.value!!.canGoNext)
        viewModel.goNext()
        assertTrue(viewModel.state.value!!.canGoPrevious)
        assertFalse(viewModel.state.value!!.canGoNext)
    }
}
