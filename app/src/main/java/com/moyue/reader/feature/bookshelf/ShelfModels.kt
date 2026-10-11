package com.moyue.reader.feature.bookshelf

import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ManagedBook
import com.moyue.reader.core.document.DocumentFormat
import com.moyue.reader.core.document.ReaderEngineRegistry

enum class ReadingStatus(val label: String) { WANT("想读"), READING("在读"), FINISHED("读完") }
enum class ShelfSort(val label: String) { RECENT("最近阅读"), IMPORTED("导入时间"), TITLE("书名"), AUTHOR("作者"), PROGRESS("阅读进度") }
data class ShelfPreferences(
    val shelfId: Long? = null,
    val favoritesOnly: Boolean = false,
    val status: ReadingStatus? = null,
    val sort: ShelfSort = ShelfSort.RECENT,
    val formats: Set<DocumentFormat> = emptySet(),
    val authors: Set<String> = emptySet(),
    val tagIds: Set<Long> = emptySet(),
)

/** Only used when a book gets its first management row. Reading writes never change this row. */
fun initialReadingStatus(progress: Float): ReadingStatus = when {
    progress >= .999f -> ReadingStatus.FINISHED
    progress > 0f -> ReadingStatus.READING
    else -> ReadingStatus.WANT
}
fun shelfBooks(library: List<ManagedBook>, preferences: ShelfPreferences, query: String = ""): List<ManagedBook> {
    val matching = library.filter { item ->
        val book = item.book
        (preferences.shelfId == null || item.shelves.any { it.id == preferences.shelfId }) &&
            (!preferences.favoritesOnly || item.management.firstOrNull()?.favorite == true) &&
            (preferences.status == null || item.status == preferences.status) &&
            (preferences.formats.isEmpty() || ReaderEngineRegistry.item(book).format in preferences.formats) &&
            (preferences.authors.isEmpty() || book.author.orEmpty() in preferences.authors) &&
            preferences.tagIds.all { id -> item.tags.any { it.id == id } } &&
            (query.isBlank() || book.title.contains(query, true) || book.author.orEmpty().contains(query, true))
    }
    val comparator: Comparator<ManagedBook> = when (preferences.sort) {
        ShelfSort.RECENT -> compareByDescending<ManagedBook> { it.book.lastReadAt ?: Long.MIN_VALUE }.thenByDescending { it.book.createdAt }
        ShelfSort.IMPORTED -> compareByDescending { it.book.createdAt }
        ShelfSort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.book.title }
        ShelfSort.AUTHOR -> compareBy<ManagedBook> { it.book.author.isNullOrBlank() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.author.orEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.title }
        ShelfSort.PROGRESS -> compareByDescending { it.book.progress }
    }
    return matching.sortedWith(comparator.thenBy { it.book.id })
}
val ManagedBook.status: ReadingStatus get() = management.firstOrNull()?.let { row -> ReadingStatus.entries.firstOrNull { it.name == row.status } } ?: initialReadingStatus(book.progress)
fun formatLabel(format: DocumentFormat): String = when (format) {
    DocumentFormat.WEB -> "缓存网页"
    DocumentFormat.MARKDOWN -> "Markdown"
    DocumentFormat.IMAGE -> "图片"
    DocumentFormat.COMIC -> "漫画"
    else -> format.name
}
