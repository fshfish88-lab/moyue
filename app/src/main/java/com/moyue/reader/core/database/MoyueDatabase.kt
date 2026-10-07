package com.moyue.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.moyue.reader.core.model.SourceType

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ReadingProgressEntity::class, DocumentMetadataEntity::class, DocumentStateEntity::class, DocumentBookmarkEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(SourceTypeConverters::class)
abstract class MoyueDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun documentDao(): DocumentDao

    companion object {
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

class SourceTypeConverters {
    @TypeConverter fun fromSourceType(value: SourceType): String = value.name
    @TypeConverter fun toSourceType(value: String): SourceType = SourceType.valueOf(value)
}
