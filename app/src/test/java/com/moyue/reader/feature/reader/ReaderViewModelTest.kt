package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewModelTest {
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
    fun chapterBoundariesDisableUnavailableDirections() = runBlocking {
        val viewModel = ReaderViewModel(ReaderRepository(FakeReaderDataSource(2)))

        viewModel.open(1, 0)
        assertFalse(viewModel.state.value!!.canGoPrevious)
        assertTrue(viewModel.state.value!!.canGoNext)
        viewModel.goNext()
        assertTrue(viewModel.state.value!!.canGoPrevious)
        assertFalse(viewModel.state.value!!.canGoNext)
    }

    private class FakeReaderDataSource(chapterCount: Int) : ReaderDataSource {
        private val chapters = List(chapterCount) { index ->
            ChapterEntity(
                id = (index + 1).toLong(),
                bookId = 1,
                title = "第${index + 1}章",
                chapterIndex = index,
                startByte = null,
                endByte = null,
                cachePath = null,
                sourceUrl = null,
                previousUrl = null,
                nextUrl = null,
            )
        }
        var storedProgress: ReadingProgressEntity? = null

        override suspend fun book(bookId: Long) = BookEntity(
            id = 1,
            title = "测试书",
            author = null,
            sourceType = SourceType.TXT,
            sourcePath = "test.txt",
            textEncoding = "UTF-8",
            sourceUrl = null,
            coverPath = null,
            chapterCount = chapters.size,
            wordCount = 100,
            createdAt = 0,
            lastReadAt = null,
            progress = 0f,
        )

        override suspend fun chapters(bookId: Long) = chapters

        override suspend fun load(book: BookEntity, chapter: ChapterEntity) = ReaderChapter(
            chapter.id,
            chapter.title,
            chapter.chapterIndex,
            listOf(ContentBlock.Text("正文")),
        )

        override suspend fun progress(bookId: Long) = storedProgress
        override suspend fun saveProgress(progress: ReadingProgressEntity) { storedProgress = progress }
    }
}
