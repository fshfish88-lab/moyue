package com.moyue.reader.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "book_management", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookManagementEntity(@PrimaryKey val bookId: Long, val status: String, val favorite: Boolean = false, val series: String? = null)
@Entity(tableName = "custom_shelves", indices = [Index(value = ["name"], unique = true)])
data class CustomShelfEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String)
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String)
@Entity(tableName = "book_shelves", primaryKeys = ["bookId", "shelfId"], foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = CustomShelfEntity::class, parentColumns = ["id"], childColumns = ["shelfId"], onDelete = ForeignKey.CASCADE)], indices = [Index("shelfId")])
data class BookShelfCrossRef(val bookId: Long, val shelfId: Long)
@Entity(tableName = "book_tags", primaryKeys = ["bookId", "tagId"], foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)], indices = [Index("tagId")])
data class BookTagCrossRef(val bookId: Long, val tagId: Long)

data class ManagedBook(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId") val management: List<BookManagementEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(BookShelfCrossRef::class, parentColumn = "bookId", entityColumn = "shelfId")) val shelves: List<CustomShelfEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(BookTagCrossRef::class, parentColumn = "bookId", entityColumn = "tagId")) val tags: List<TagEntity>,
)
@Dao
interface ShelfDao {
    @Transaction @Query("SELECT * FROM books ORDER BY id") fun observeLibrary(): Flow<List<ManagedBook>>
    @Transaction @Query("SELECT * FROM books ORDER BY id") suspend fun library(): List<ManagedBook>
    @Query("SELECT * FROM custom_shelves ORDER BY name COLLATE NOCASE, id") fun observeShelves(): Flow<List<CustomShelfEntity>>
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id") fun observeTags(): Flow<List<TagEntity>>
    @Query("INSERT OR IGNORE INTO book_management(bookId,status,favorite,series) SELECT id, CASE WHEN progress >= 0.999 THEN 'FINISHED' WHEN progress > 0 THEN 'READING' ELSE 'WANT' END, 0, NULL FROM books") suspend fun initializeMissing()
    @Query("UPDATE book_management SET status=:status WHERE bookId IN (:ids)") suspend fun status(ids: List<Long>, status: String)
    @Query("UPDATE book_management SET favorite=:value WHERE bookId IN (:ids)") suspend fun favorite(ids: List<Long>, value: Boolean)
    @Query("UPDATE book_management SET series=:value WHERE bookId=:id") suspend fun series(id: Long, value: String?)
    @Insert suspend fun createShelf(value: CustomShelfEntity): Long
    @Insert suspend fun createTag(value: TagEntity): Long
    @Query("UPDATE custom_shelves SET name=:name WHERE id=:id") suspend fun renameShelf(id: Long, name: String): Int
    @Query("UPDATE tags SET name=:name WHERE id=:id") suspend fun renameTag(id: Long, name: String): Int
    @Query("DELETE FROM custom_shelves WHERE id=:id") suspend fun deleteShelf(id: Long)
    @Query("DELETE FROM tags WHERE id=:id") suspend fun deleteTag(id: Long)
    @Query("SELECT COUNT(*) FROM custom_shelves WHERE name=:name COLLATE NOCASE AND id!=:exceptId") suspend fun shelfNameCount(name: String, exceptId: Long = -1): Int
    @Query("SELECT COUNT(*) FROM tags WHERE name=:name COLLATE NOCASE AND id!=:exceptId") suspend fun tagNameCount(name: String, exceptId: Long = -1): Int
    @Query("SELECT COUNT(*) FROM custom_shelves WHERE id=:id") suspend fun shelfExists(id: Long): Int
    @Query("SELECT COUNT(*) FROM tags WHERE id=:id") suspend fun tagExists(id: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addShelves(values: List<BookShelfCrossRef>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTags(values: List<BookTagCrossRef>)
    @Query("DELETE FROM book_shelves WHERE bookId IN (:books) AND shelfId IN (:shelves)") suspend fun removeShelves(books: List<Long>, shelves: List<Long>)
    @Query("DELETE FROM book_tags WHERE bookId IN (:books) AND tagId IN (:tags)") suspend fun removeTags(books: List<Long>, tags: List<Long>)
}
