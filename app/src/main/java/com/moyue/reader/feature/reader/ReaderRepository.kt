package com.moyue.reader.feature.reader

import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.MoyueDatabase
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.parser.txt.TxtChapterLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

interface ReaderDataSource {
    suspend fun book(bookId: Long): BookEntity?
    suspend fun chapters(bookId: Long): List<ChapterEntity>
    suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter
    suspend fun progress(bookId: Long): ReadingProgressEntity?
    suspend fun saveProgress(progress: ReadingProgressEntity)
}

data class ReaderSession(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val chapter: ReaderChapter,
    val restoredProgress: ReadingProgressEntity?,
)

class ReaderRepository(private val source: ReaderDataSource) {
    private val cache = linkedMapOf<Int, ReaderChapter>()
    private var currentBookId: Long? = null
    private var currentBook: BookEntity? = null
    private var chapterList: List<ChapterEntity> = emptyList()

    suspend fun open(bookId: Long, requestedIndex: Int? = null): ReaderSession {
        if (currentBookId != bookId) cache.clear()
        val book = requireNotNull(source.book(bookId)) { "找不到书籍" }
        val chapters = source.chapters(bookId)
        require(chapters.isNotEmpty()) { "书籍没有可阅读章节" }
        val progress = source.progress(bookId)
        val restoredIndex = progress?.let { stored ->
            chapters.indexOfFirst { it.id == stored.chapterId }.takeIf { it >= 0 }
        }
        val index = (requestedIndex ?: restoredIndex ?: 0).coerceIn(chapters.indices)
        currentBookId = bookId
        currentBook = book
        chapterList = chapters
        warm(index)
        return ReaderSession(book, chapters, requireNotNull(cache[index]), progress)
    }

    suspend fun chapter(index: Int): ReaderChapter {
        require(index in chapterList.indices) { "章节超出范围" }
        warm(index)
        return requireNotNull(cache[index])
    }

    suspend fun saveProgress(progress: ReadingProgressEntity) = source.saveProgress(progress)

    fun cachedChapterIndexes(): Set<Int> = cache.keys.toSet()

    private suspend fun warm(index: Int) {
        val book = requireNotNull(currentBook)
        val keep = setOf(index - 1, index, index + 1).filter { it in chapterList.indices }.toSet()
        keep.forEach { chapterIndex ->
            if (chapterIndex !in cache) {
                cache[chapterIndex] = source.load(book, chapterList[chapterIndex])
            }
        }
        cache.keys.toList().filterNot(keep::contains).forEach(cache::remove)
    }
}

class RoomReaderDataSource(
    private val database: MoyueDatabase,
    private val txtLoader: TxtChapterLoader = TxtChapterLoader(),
) : ReaderDataSource {
    override suspend fun book(bookId: Long) = database.bookDao().get(bookId)
    override suspend fun chapters(bookId: Long) = database.chapterDao().forBook(bookId)
    override suspend fun progress(bookId: Long) = database.readingProgressDao().get(bookId)

    override suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter =
        when (book.sourceType) {
            SourceType.TXT -> txtLoader.load(
                File(book.sourcePath),
                chapter,
                Charset.forName(book.textEncoding ?: "UTF-8"),
            )
            SourceType.EPUB, SourceType.WEB -> loadNormalized(chapter)
        }

    override suspend fun saveProgress(progress: ReadingProgressEntity) {
        database.readingProgressDao().upsert(progress)
        database.bookDao().get(progress.bookId)?.let { book ->
            database.bookDao().update(
                book.copy(lastReadAt = progress.updatedAt, progress = progress.bookProgress),
            )
        }
    }

    private suspend fun loadNormalized(chapter: ChapterEntity): ReaderChapter =
        withContext(Dispatchers.IO) {
            val file = File(requireNotNull(chapter.cachePath) { "章节缓存不存在" })
            val blocks = file.readText().split(Regex("\\R\\s*\\R"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(ContentBlock::Text)
            ReaderChapter(chapter.id, chapter.title, chapter.chapterIndex, blocks)
        }
}
