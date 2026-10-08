package com.moyue.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.moyue.reader.core.model.SourceType

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ReadingProgressEntity::class, DocumentMetadataEntity::class, DocumentStateEntity::class, DocumentBookmarkEntity::class, AnnotationEntity::class, SearchChunkEntity::class, SearchFtsEntity::class, SearchStateEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(SourceTypeConverters::class)
abstract class MoyueDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun documentDao(): DocumentDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun searchDao(): SearchDao

    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS annotations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, type TEXT NOT NULL, anchorJson TEXT NOT NULL, selectedText TEXT NOT NULL, note TEXT NOT NULL, color TEXT NOT NULL, location TEXT NOT NULL, sourceHash TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS search_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, partKey TEXT NOT NULL, location TEXT NOT NULL, anchorJson TEXT NOT NULL, text TEXT NOT NULL, sourceHash TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_chunks_bookId ON search_chunks(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_chunks_bookId_partKey ON search_chunks(bookId, partKey)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING FTS4(tokens TEXT NOT NULL, tokenize=unicode61)")
                db.execSQL("CREATE TABLE IF NOT EXISTS search_states (bookId INTEGER PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL, indexedParts INTEGER NOT NULL, totalParts INTEGER NOT NULL, message TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.query("SELECT d.*, m.format FROM document_bookmarks d LEFT JOIN document_metadata m ON m.bookId=d.bookId").use { cursor ->
                    while (cursor.moveToNext()) {
                        fun str(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
                        fun num(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                        val kind = if (str("format") == "PDF") "PDF" else "VISUAL"
                        val position = runCatching { org.json.JSONObject(str("positionJson")) }.getOrElse { org.json.JSONObject().put("invalidLegacy", true).put("raw", str("positionJson")) }
                        val anchor = org.json.JSONObject().put("kind", kind).put("position", position).toString()
                        db.execSQL("INSERT INTO annotations(id,bookId,type,anchorJson,selectedText,note,color,location,sourceHash,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(num("id"), num("bookId"), "BOOKMARK", anchor, "", "", "yellow", str("title"), "", num("createdAt"), num("createdAt")))
                    }
                }
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS document_metadata (bookId INTEGER NOT NULL PRIMARY KEY, format TEXT NOT NULL, mimeType TEXT NOT NULL, byteSize INTEGER NOT NULL, pageCount INTEGER, locked INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS document_states (bookId INTEGER NOT NULL PRIMARY KEY, engineId TEXT NOT NULL, stateVersion INTEGER NOT NULL, sourceHash TEXT NOT NULL, positionJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS document_bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, positionJson TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_bookmarks_bookId ON document_bookmarks(bookId)")
            }
        }
        @Volatile private var instance: MoyueDatabase? = null

        fun get(context: Context): MoyueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MoyueDatabase::class.java,
                "moyue.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}

class SourceTypeConverters {
    @TypeConverter fun fromSourceType(value: SourceType): String = value.name
    @TypeConverter fun toSourceType(value: String): SourceType = SourceType.valueOf(value)
}
