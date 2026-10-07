package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.SourceType

/** In-memory reader source shared by the reader progress tests. */
internal class FakeReaderDataSource(chapterCount: Int) : ReaderDataSource {
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
    var failureIndex: Int? = null
    var failSave = false

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

    override suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter {
        check(chapter.chapterIndex != failureIndex) { "EPUB 章节缺失" }
        return ReaderChapter(
            chapter.id,
            chapter.title,
            chapter.chapterIndex,
            List(3) { ContentBlock.Text("正文 abcdefghijklmnop") },
        )
    }

    override suspend fun progress(bookId: Long) = storedProgress
    override suspend fun saveProgress(progress: ReadingProgressEntity) { check(!failSave) { "disk full" }; storedProgress = progress }
}
