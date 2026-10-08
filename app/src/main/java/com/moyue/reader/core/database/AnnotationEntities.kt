package com.moyue.reader.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "annotations", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long, val type: String, val anchorJson: String, val selectedText: String,
    val note: String, val color: String, val location: String, val sourceHash: String,
    val createdAt: Long, val updatedAt: Long,
)

data class AnnotationItem(@Embedded val annotation: AnnotationEntity, val bookTitle: String)

@Entity(tableName = "search_chunks", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId"), Index(value = ["bookId", "partKey"])])
data class SearchChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: Long, val partKey: String,
    val location: String, val anchorJson: String, val text: String, val sourceHash: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(@PrimaryKey @ColumnInfo(name = "rowid") val id: Long, val tokens: String)

@Entity(tableName = "search_states", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class SearchStateEntity(@PrimaryKey val bookId: Long, val fingerprint: String, val status: String, val indexedParts: Int, val totalParts: Int, val message: String)

data class SearchHit(@Embedded val chunk: SearchChunkEntity, val bookTitle: String)

@Dao
interface AnnotationDao {
    @Query("SELECT a.*, b.title AS bookTitle FROM annotations a JOIN books b ON b.id=a.bookId ORDER BY a.createdAt DESC")
    fun observeAll(): Flow<List<AnnotationItem>>
    @Query("SELECT * FROM annotations WHERE bookId=:bookId ORDER BY createdAt DESC")
    fun forBook(bookId: Long): Flow<List<AnnotationEntity>>
    @Query("SELECT * FROM annotations WHERE bookId=:bookId ORDER BY updatedAt DESC, id DESC")
    suspend fun listForBook(bookId: Long): List<AnnotationEntity>
    @Insert suspend fun insert(annotation: AnnotationEntity): Long
    @Update suspend fun update(annotation: AnnotationEntity)
    @Query("DELETE FROM annotations WHERE id=:id") suspend fun delete(id: Long)
}

@Dao
interface SearchDao {
    @Insert suspend fun addChunk(chunk: SearchChunkEntity): Long
    @Insert suspend fun addTerms(terms: SearchFtsEntity)
    @Query("DELETE FROM search_fts WHERE rowid IN (SELECT id FROM search_chunks WHERE bookId=:bookId AND partKey=:key)") suspend fun deletePartTerms(bookId: Long, key: String)
    @Query("DELETE FROM search_chunks WHERE bookId=:bookId AND partKey=:key") suspend fun deletePart(bookId: Long, key: String)
    @Query("DELETE FROM search_fts WHERE rowid IN (SELECT id FROM search_chunks WHERE bookId=:bookId)") suspend fun deleteBookTerms(bookId: Long)
    @Query("DELETE FROM search_chunks WHERE bookId=:bookId") suspend fun deleteBook(bookId: Long)
    @Query("DELETE FROM search_fts WHERE rowid NOT IN (SELECT id FROM search_chunks)") suspend fun prune()
    @Query("SELECT c.*, b.title AS bookTitle FROM search_chunks c JOIN search_fts f ON f.rowid=c.id JOIN books b ON b.id=c.bookId WHERE search_fts MATCH :terms AND ((b.sourceType IN ('TXT','EPUB','WEB') AND c.partKey LIKE 'catalog:%') OR (b.sourceType IN ('MARKDOWN','DOCUMENT') AND c.partKey NOT LIKE 'chapter:%')) AND (:scope='ALL' OR (:scope='CATALOG' AND c.partKey LIKE 'catalog:%') OR (:scope='BODY' AND b.sourceType IN ('MARKDOWN','DOCUMENT'))) AND instr(lower(c.text), lower(:query))>0 ORDER BY b.title, c.id LIMIT :limit OFFSET :offset")
    suspend fun search(terms: String, query: String, limit: Int, offset: Int, scope: String = "ALL"): List<SearchHit>
    @Query("SELECT * FROM search_states ORDER BY bookId") fun observeStates(): Flow<List<SearchStateEntity>>
    @Query("SELECT * FROM search_states WHERE bookId=:bookId") suspend fun state(bookId: Long): SearchStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun state(value: SearchStateEntity)
}
