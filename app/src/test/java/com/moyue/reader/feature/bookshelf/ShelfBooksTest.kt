package com.moyue.reader.feature.bookshelf

import com.moyue.reader.core.database.*
import com.moyue.reader.core.document.DocumentFormat
import com.moyue.reader.core.model.SourceType
import org.junit.Assert.*
import org.junit.Test

class ShelfBooksTest {
    private fun item(id: Long, title: String, author: String?, progress: Float, favorite: Boolean = false,
        state: ReadingStatus = ReadingStatus.WANT, source: SourceType = SourceType.TXT, path: String = "/test.txt",
        shelves: List<Long> = emptyList(), tags: List<Long> = emptyList(), imported: Long = id, recent: Long? = null) = ManagedBook(
        BookEntity(id, title, author, source, path, null, null, null, 1, 100, imported, recent, progress),
        listOf(BookManagementEntity(id, state.name, favorite)), shelves.map { CustomShelfEntity(it, "书架$it") }, tags.map { TagEntity(it, "标签$it") })
    private val books = listOf(
        item(1, "Zulu", "B", 1f, true, ReadingStatus.WANT, shelves = listOf(10, 11), tags = listOf(20, 21), imported = 100, recent = 50),
        item(2, "alpha", "A", .4f, false, ReadingStatus.READING, shelves = listOf(10), tags = listOf(20), imported = 200, recent = 60),
        item(3, "Beta", null, .8f, true, ReadingStatus.FINISHED, SourceType.DOCUMENT, "/test.pdf", listOf(11), listOf(21), 300),
        item(4, "Gamma", "A", 0f, true, ReadingStatus.WANT, SourceType.EPUB, "/test.epub", listOf(10), listOf(20,21), 400),
    )
    private fun ids(p: ShelfPreferences) = shelfBooks(books, p).map { it.book.id }
    @Test fun `composed shelf favorite state format author and all tags`() {
        assertEquals(listOf(1L), ids(ShelfPreferences(shelfId = 10, favoritesOnly = true, status = ReadingStatus.WANT,
            formats = setOf(DocumentFormat.TXT), authors = setOf("B"), tagIds = setOf(20,21))))
        assertTrue(ids(ShelfPreferences(shelfId = 11, status = ReadingStatus.READING)).isEmpty())
        assertEquals(setOf(1L,4L), ids(ShelfPreferences(tagIds = setOf(20,21))).toSet())
        assertEquals(setOf(1L,2L,4L), ids(ShelfPreferences(formats = setOf(DocumentFormat.TXT, DocumentFormat.EPUB))).toSet())
        assertEquals(listOf(3L), ids(ShelfPreferences(authors = setOf(""))))
    }
    @Test fun `manual states and favorites are independent of progress`() {
        assertEquals(setOf(1L,4L), ids(ShelfPreferences(status = ReadingStatus.WANT)).toSet())
        assertEquals(listOf(3L), ids(ShelfPreferences(status = ReadingStatus.FINISHED)))
        assertEquals(setOf(1L,3L,4L), ids(ShelfPreferences(favoritesOnly = true)).toSet())
    }
    @Test fun `all sort orders are deterministic`() {
        assertEquals(listOf(2L,1L,4L,3L), ids(ShelfPreferences(sort = ShelfSort.RECENT)))
        assertEquals(listOf(4L,3L,2L,1L), ids(ShelfPreferences(sort = ShelfSort.IMPORTED)))
        assertEquals(listOf(2L,3L,4L,1L), ids(ShelfPreferences(sort = ShelfSort.TITLE)))
        assertEquals(listOf(2L,4L,1L,3L), ids(ShelfPreferences(sort = ShelfSort.AUTHOR)))
        assertEquals(listOf(1L,3L,2L,4L), ids(ShelfPreferences(sort = ShelfSort.PROGRESS)))
        val ties = listOf(books[0].copy(book = books[0].book.copy(id = 9)), books[0])
        assertEquals(listOf(1L,9L), shelfBooks(ties, ShelfPreferences(sort = ShelfSort.PROGRESS)).map { it.book.id })
    }
    @Test fun `initial states only establish migration defaults`() {
        assertEquals(ReadingStatus.WANT, initialReadingStatus(0f))
        assertEquals(ReadingStatus.READING, initialReadingStatus(.5f))
        assertEquals(ReadingStatus.FINISHED, initialReadingStatus(1f))
        assertEquals(ReadingStatus.WANT, books[0].copy(book = books[0].book.copy(progress = 1f)).status)
    }
    @Test fun `title and author query respects filters`() {
        assertEquals(listOf(2L), shelfBooks(books, ShelfPreferences(shelfId = 10), "ALP").map { it.book.id })
        assertEquals(setOf(2L,4L), shelfBooks(books, ShelfPreferences(), "A").filter { it.book.author == "A" }.map { it.book.id }.toSet())
        assertTrue(shelfBooks(books, ShelfPreferences(favoritesOnly = true), "alpha").isEmpty())
    }
    @Test fun `image comic and markdown formats remain distinct`() {
        val mixed = listOf(item(1,"图片",null,0f,source=SourceType.DOCUMENT,path="/a.JPG"),
            item(2,"漫画",null,0f,source=SourceType.DOCUMENT,path="/a.cbz"), item(3,"笔记",null,0f,source=SourceType.MARKDOWN))
        for ((id,format) in listOf(1L to DocumentFormat.IMAGE,2L to DocumentFormat.COMIC,3L to DocumentFormat.MARKDOWN))
            assertEquals(listOf(id), shelfBooks(mixed,ShelfPreferences(formats=setOf(format))).map{it.book.id})
    }
}
