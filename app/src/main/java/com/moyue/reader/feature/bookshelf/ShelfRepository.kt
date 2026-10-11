package com.moyue.reader.feature.bookshelf

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.moyue.reader.core.database.*
import com.moyue.reader.core.document.DocumentFormat
import kotlinx.coroutines.flow.*

private val Context.shelfDataStore by preferencesDataStore(name = "bookshelf_preferences")
class ShelfPreferencesRepository(private val context: Context) {
    private val shelf = longPreferencesKey("shelf")
    private val favorite = booleanPreferencesKey("favorite")
    private val status = stringPreferencesKey("status")
    private val sort = stringPreferencesKey("sort")
    private val formats = stringSetPreferencesKey("formats")
    private val authors = stringSetPreferencesKey("authors")
    private val tags = stringSetPreferencesKey("tags")
    private fun decode(p: Preferences) = ShelfPreferences(
        shelfId = p[shelf], favoritesOnly = p[favorite] ?: false,
        status = ReadingStatus.entries.firstOrNull { it.name == p[status] },
        sort = ShelfSort.entries.firstOrNull { it.name == p[sort] } ?: ShelfSort.RECENT,
        formats = p[formats].orEmpty().mapNotNull { name -> DocumentFormat.entries.firstOrNull { it.name == name } }.toSet(),
        authors = p[authors].orEmpty(), tagIds = p[tags].orEmpty().mapNotNull(String::toLongOrNull).toSet(),
    )
    val preferences = context.shelfDataStore.data.map(::decode)
    suspend fun update(transform: (ShelfPreferences) -> ShelfPreferences) {
        context.shelfDataStore.edit { p ->
            val value = transform(decode(p))
            value.shelfId?.let { p[shelf] = it } ?: p.remove(shelf)
            p[favorite] = value.favoritesOnly
            value.status?.let { p[status] = it.name } ?: p.remove(status)
            p[sort] = value.sort.name
            p[formats] = value.formats.map { it.name }.toSet()
            p[authors] = value.authors
            p[tags] = value.tagIds.map(Long::toString).toSet()
        }
    }
}
data class ShelfLibrary(val books: List<ManagedBook> = emptyList(), val shelves: List<CustomShelfEntity> = emptyList(), val tags: List<TagEntity> = emptyList())
class ShelfRepository(private val db: MoyueDatabase, val preferences: ShelfPreferencesRepository) {
    private val dao = db.shelfDao()
    val library = combine(
        dao.observeLibrary().onEach { if (it.any { book -> book.management.isEmpty() }) dao.initializeMissing() },
        dao.observeShelves(), dao.observeTags(), ::ShelfLibrary,
    )
    private suspend fun manage(ids: Set<Long>, action: suspend (List<Long>) -> Unit) = db.withTransaction {
        require(ids.isNotEmpty()) { "请先选择书籍" }
        require(ids.all { db.bookDao().get(it) != null }) { "部分书籍已删除，请重新选择" }
        dao.initializeMissing()
        action(ids.toList())
    }
    suspend fun status(ids: Set<Long>, value: ReadingStatus) = manage(ids) { dao.status(it, value.name) }
    suspend fun favorite(ids: Set<Long>, value: Boolean) = manage(ids) { dao.favorite(it, value) }
    suspend fun series(id: Long, value: String) = manage(setOf(id)) { dao.series(id, value.trim().takeIf(String::isNotEmpty)) }
    private fun name(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 100) { "名称需为 1–100 个字符" } }
    suspend fun saveCategory(tag: Boolean, id: Long?, value: String): Long = db.withTransaction {
        val clean = name(value)
        require((if (tag) dao.tagNameCount(clean, id ?: -1) else dao.shelfNameCount(clean, id ?: -1)) == 0) { "已存在同名分类" }
        if (id == null) {
            if (tag) dao.createTag(TagEntity(name = clean)) else dao.createShelf(CustomShelfEntity(name = clean))
        } else {
            require((if (tag) dao.renameTag(id, clean) else dao.renameShelf(id, clean)) == 1) { "分类已删除" }
            id
        }
    }
    suspend fun deleteCategory(tag: Boolean, id: Long) {
        db.withTransaction { if (tag) dao.deleteTag(id) else dao.deleteShelf(id) }
        preferences.update { if (tag) it.copy(tagIds = it.tagIds - id) else if (it.shelfId == id) it.copy(shelfId = null) else it }
    }
    /** Deltas preserve unrelated memberships; bulk changes never replace each book's other categories. */
    suspend fun categories(ids: Set<Long>, tag: Boolean, add: Set<Long>, remove: Set<Long>) = manage(ids) { books ->
        require(add.intersect(remove).isEmpty()) { "不能同时添加和移除同一分类" }
        require(add.all { (if (tag) dao.tagExists(it) else dao.shelfExists(it)) == 1 }) { "分类已删除，请重新选择" }
        if (tag) {
            dao.removeTags(books, remove.toList())
            dao.addTags(books.flatMap { b -> add.map { BookTagCrossRef(b, it) } })
        } else {
            dao.removeShelves(books, remove.toList())
            dao.addShelves(books.flatMap { b -> add.map { BookShelfCrossRef(b, it) } })
        }
    }
}
