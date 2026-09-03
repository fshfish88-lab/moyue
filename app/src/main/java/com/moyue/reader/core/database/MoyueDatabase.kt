package com.moyue.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.moyue.reader.core.model.SourceType

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ReadingProgressEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SourceTypeConverters::class)
abstract class MoyueDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        @Volatile private var instance: MoyueDatabase? = null

        fun get(context: Context): MoyueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MoyueDatabase::class.java,
                "moyue.db",
            ).build().also { instance = it }
        }
    }
}

class SourceTypeConverters {
    @TypeConverter fun fromSourceType(value: SourceType): String = value.name
    @TypeConverter fun toSourceType(value: String): SourceType = SourceType.valueOf(value)
}
