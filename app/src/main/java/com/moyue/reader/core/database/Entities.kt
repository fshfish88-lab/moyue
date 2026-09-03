package com.moyue.reader.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moyue.reader.core.model.SourceType

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val sourceType: SourceType,
    val sourcePath: String,
    val textEncoding: String?,
    val sourceUrl: String?,
    val coverPath: String?,
    val chapterCount: Int,
    val wordCount: Long,
    val createdAt: Long,
    val lastReadAt: Long?,
    val progress: Float,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val chapterIndex: Int,
    val startByte: Long?,
    val endByte: Long?,
    val cachePath: String?,
    val sourceUrl: String?,
    val previousUrl: String?,
    val nextUrl: String?,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chapterId")],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    val blockIndex: Int,
    val charOffset: Int,
    val chapterProgress: Float,
    val bookProgress: Float,
    val updatedAt: Long,
)
