package com.moyue.reader.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, createdAt) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Insert suspend fun insert(book: BookEntity): Long
    @Update suspend fun update(book: BookEntity)
    @Delete suspend fun delete(book: BookEntity)
    @Query("UPDATE books SET progress = :progress, lastReadAt = :time WHERE id = :id") suspend fun updateReading(id: Long, progress: Float, time: Long)
    @Query("UPDATE books SET coverPath = :path WHERE id = :id") suspend fun updateCover(id: Long, path: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters ORDER BY id")
    fun observeCatalog(): Flow<List<ChapterEntity>>

    @Update suspend fun update(chapter: ChapterEntity)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun forBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM document_metadata WHERE bookId = :bookId") suspend fun metadata(bookId: Long): DocumentMetadataEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMetadata(value: DocumentMetadataEntity)
    @Query("SELECT * FROM document_states WHERE bookId = :bookId") suspend fun state(bookId: Long): DocumentStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertState(value: DocumentStateEntity)
    @Query("SELECT * FROM document_bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC") fun bookmarks(bookId: Long): Flow<List<DocumentBookmarkEntity>>
    @Insert suspend fun addBookmark(value: DocumentBookmarkEntity): Long
    @Query("DELETE FROM document_bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
}
