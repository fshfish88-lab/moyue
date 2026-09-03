package com.moyue.reader.core.storage

import androidx.room.withTransaction
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.MoyueDatabase
import com.moyue.reader.core.model.ParsedBook
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportStage
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ImportRequest(
    val taskId: String,
    val source: File,
    val sourceType: SourceType,
    val sourceExtension: String,
    val sourceUrl: String? = null,
)

data class PreparedImport(
    val taskId: String,
    val stagedDirectory: File,
    val stagedSource: File,
    val sourceExtension: String,
    val sourceUrl: String?,
    val book: ParsedBook,
)

interface ImportProcessor {
    suspend fun detect(source: File)
    suspend fun parse(source: File, sourceType: SourceType): ParsedBook
    suspend fun validate(book: ParsedBook)
}

fun interface AtomicImportStore {
    suspend fun commit(prepared: PreparedImport): Long
}

class RecoverableImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class FatalImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ImportCancelledException : CancellationException("Import cancelled")

class AtomicImportCoordinator(
    private val storage: BookStorage,
    private val store: AtomicImportStore,
) {
    suspend fun import(
        request: ImportRequest,
        processor: ImportProcessor,
        onProgress: (ImportState.Running) -> Unit = {},
    ): ImportState {
        val tempDirectory = storage.importTemp(request.taskId)
        return try {
            progress(request.taskId, ImportStage.WAITING, 0f, onProgress)
            prepareEmptyDirectory(tempDirectory)

            progress(request.taskId, ImportStage.COPYING, 0.12f, onProgress)
            val stagedSource = tempDirectory.resolve("source.${safeExtension(request.sourceExtension)}")
            request.source.inputStream().buffered().use { input ->
                stagedSource.outputStream().buffered().use(input::copyTo)
            }

            progress(request.taskId, ImportStage.DETECTING, 0.3f, onProgress)
            processor.detect(stagedSource)

            progress(request.taskId, ImportStage.PARSING, 0.5f, onProgress)
            val parsedBook = processor.parse(stagedSource, request.sourceType)
            processor.validate(parsedBook)

            progress(request.taskId, ImportStage.SAVING, 0.85f, onProgress)
            val bookId = store.commit(
                PreparedImport(
                    taskId = request.taskId,
                    stagedDirectory = tempDirectory,
                    stagedSource = stagedSource,
                    sourceExtension = safeExtension(request.sourceExtension),
                    sourceUrl = request.sourceUrl,
                    book = parsedBook,
                ),
            )
            tempDirectory.deleteRecursively()
            progress(request.taskId, ImportStage.COMPLETED, 1f, onProgress)
            ImportState.Completed(bookId)
        } catch (_: ImportCancelledException) {
            tempDirectory.deleteRecursively()
            ImportState.Cancelled(request.taskId)
        } catch (_: CancellationException) {
            tempDirectory.deleteRecursively()
            ImportState.Cancelled(request.taskId)
        } catch (error: RecoverableImportException) {
            tempDirectory.deleteRecursively()
            ImportState.RecoverableError(request.taskId, error.message ?: "导入失败，可重试")
        } catch (error: FatalImportException) {
            tempDirectory.deleteRecursively()
            ImportState.FatalError(request.taskId, error.message ?: "导入失败")
        } catch (error: IOException) {
            tempDirectory.deleteRecursively()
            ImportState.RecoverableError(request.taskId, error.message ?: "文件读取失败")
        } catch (error: Exception) {
            tempDirectory.deleteRecursively()
            ImportState.FatalError(request.taskId, error.message ?: "导入失败")
        }
    }

    fun cleanupAbandonedImports() {
        val root = storage.importTemp("cleanup-probe").parentFile ?: return
        root.listFiles()?.forEach(File::deleteRecursively)
    }

    private fun prepareEmptyDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("无法清理旧的导入任务")
        }
        if (!directory.mkdirs()) throw IOException("无法创建导入临时目录")
    }

    private fun safeExtension(extension: String): String {
        val normalized = extension.lowercase().removePrefix(".")
        if (normalized !in setOf("txt", "epub", "html")) {
            throw FatalImportException("不支持的文件格式")
        }
        return normalized
    }

    private fun progress(
        taskId: String,
        stage: ImportStage,
        progress: Float,
        observer: (ImportState.Running) -> Unit,
    ) = observer(ImportState.Running(taskId, stage, progress))
}

class RoomAtomicImportStore(
    private val database: MoyueDatabase,
    private val storage: BookStorage,
    private val now: () -> Long = System::currentTimeMillis,
) : AtomicImportStore {
    override suspend fun commit(prepared: PreparedImport): Long {
        var publishedDirectory: File? = null
        try {
            return database.withTransaction {
                val bookDao = database.bookDao()
                val chapterDao = database.chapterDao()
                val draft = BookEntity(
                    title = prepared.book.title,
                    author = prepared.book.author,
                    sourceType = prepared.book.sourceType,
                    sourcePath = "",
                    sourceUrl = prepared.sourceUrl,
                    coverPath = prepared.book.coverPath,
                    chapterCount = prepared.book.chapters.size,
                    wordCount = prepared.book.wordCount,
                    createdAt = now(),
                    lastReadAt = null,
                    progress = 0f,
                )
                val bookId = bookDao.insert(draft)
                val destination = storage.bookDirectory(bookId)
                publishDirectory(prepared.stagedDirectory, destination)
                publishedDirectory = destination

                val finalSource = storage.sourceFile(bookId, prepared.sourceExtension)
                bookDao.update(
                    draft.copy(
                        id = bookId,
                        sourcePath = finalSource.absolutePath,
                        coverPath = relocate(prepared.book.coverPath, prepared.stagedDirectory, destination),
                    ),
                )
                chapterDao.insertAll(
                    prepared.book.chapters.map { chapter ->
                        ChapterEntity(
                            bookId = bookId,
                            title = chapter.title,
                            chapterIndex = chapter.index,
                            startByte = chapter.startByte,
                            endByte = chapter.endByte,
                            cachePath = relocate(chapter.cachePath, prepared.stagedDirectory, destination),
                            sourceUrl = chapter.sourceUrl,
                            previousUrl = chapter.previousUrl,
                            nextUrl = chapter.nextUrl,
                        )
                    },
                )
                bookId
            }
        } catch (error: Exception) {
            publishedDirectory?.deleteRecursively()
            throw error
        }
    }

    private fun publishDirectory(source: File, destination: File) {
        if (destination.exists()) throw IOException("目标书籍目录已存在")
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun relocate(path: String?, from: File, to: File): String? {
        if (path == null) return null
        val candidate = File(path)
        if (!candidate.isAbsolute) return path
        return if (candidate.toPath().startsWith(from.toPath())) {
            to.toPath().resolve(from.toPath().relativize(candidate.toPath())).toFile().absolutePath
        } else {
            path
        }
    }
}
