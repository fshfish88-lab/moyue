package com.moyue.reader.feature.markdown

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.MoyueDatabase
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.storage.BookStorage
import com.moyue.reader.parser.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class MarkdownDocument(val book: BookEntity, val text: String, val state: JSONObject, val recovery: JSONObject?)

/** A gate belongs to one editing session, never reused across reopened documents. */
class MarkdownRevisionGate {
    private var last = -1L
    fun accepts(revision: Long) = revision > last
    fun committed(revision: Long) { require(revision > last); last = revision }
}

class MarkdownRepository(
    private val context: Context,
    private val database: MoyueDatabase,
    private val storage: BookStorage,
) {
    private val mutex = Mutex()

    suspend fun open(id: Long): MarkdownDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = requireNotNull(database.bookDao().get(id)) { "文档不存在" }
            require(book.sourceType == SourceType.MARKDOWN)
            val bytes = AtomicFile(File(book.sourcePath)).openRead().use { it.readBytes() }
            val text = MarkdownText.decode(bytes)
            val hash = hash(bytes)
            val root = storage.bookDirectory(id)
            val state = readJson(root.resolve("state.json")) ?: JSONObject()
            val draft = readJson(root.resolve("draft.json"))
            val recovery = draft?.takeIf { it.optString("contentHash") != hash }
            // Book fields are an index of the source. Repair an interrupted metadata update on open.
            database.bookDao().update(book.copy(wordCount = MarkdownText.wordCount(text), lastReadAt = System.currentTimeMillis()))
            MarkdownDocument(book, text, state, recovery)
        }
    }

    suspend fun save(id: Long, text: String, revision: Long, gate: MarkdownRevisionGate): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!gate.accepts(revision)) return@withLock false
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MarkdownText.EDIT_BYTES) { "文档超过 1 MB，暂不支持编辑" }
            val book = requireNotNull(database.bookDao().get(id))
            require(book.sourceType == SourceType.MARKDOWN)
            val source = File(book.sourcePath)
            val draft = JSONObject().put("text", text).put("revision", revision)
                .put("baseHash", hash(AtomicFile(source).openRead().use { it.readBytes() }))
                .put("contentHash", hash(bytes))
            atomicWrite(storage.bookDirectory(id).resolve("draft.json"), draft.toString().toByteArray())
            atomicWrite(source, bytes)
            gate.committed(revision)
            // Source has committed. A failed index update must not turn a successful write into failure.
            runCatching { database.bookDao().update(book.copy(wordCount = MarkdownText.wordCount(text))) }
            true
        }
    }

    suspend fun checkpoint(id: Long, text: String, revision: Long, gate: MarkdownRevisionGate? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (gate != null && !gate.accepts(revision)) return@withLock
            val book = requireNotNull(database.bookDao().get(id))
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MarkdownText.EDIT_BYTES)
            val draft = JSONObject().put("text", text).put("revision", revision)
                .put("baseHash", hash(AtomicFile(File(book.sourcePath)).openRead().use { it.readBytes() }))
                .put("contentHash", hash(bytes))
            atomicWrite(storage.bookDirectory(id).resolve("draft.json"), draft.toString().toByteArray())
        }
    }

    suspend fun discardDraft(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { AtomicFile(storage.bookDirectory(id).resolve("draft.json")).delete() }
    }

    suspend fun savePosition(id: Long, position: JSONObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = database.bookDao().get(id) ?: return@withLock
            position.put("fileHash", hash(AtomicFile(File(book.sourcePath)).openRead().use { it.readBytes() }))
            atomicWrite(storage.bookDirectory(id).resolve("state.json"), position.toString().toByteArray())
            runCatching { database.bookDao().update(book.copy(lastReadAt = System.currentTimeMillis(), progress = position.optDouble("ratio", 0.0).toFloat().coerceIn(0f, 1f))) }
        }
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = requireNotNull(database.bookDao().get(id))
            database.bookDao().update(book.copy(title = MarkdownText.safeName(name.removeSuffix(".md"))))
        }
    }

    suspend fun insertImage(id: Long, uri: Uri): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val mime = context.contentResolver.getType(uri).orEmpty()
            require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "请选择 PNG/JPEG/WebP/GIF 图片" }
            val ext = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; else -> "webp" }
            val file = storage.bookDirectory(id).resolve("assets/${UUID.randomUUID()}.$ext")
            file.parentFile?.mkdirs()
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0
                        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }; output.write(buffer, 0, n) }
                    }
                }
                "assets/${file.name}"
            } catch (error: Exception) { file.delete(); throw error }
        }
    }

    suspend fun export(id: Long, uri: Uri, zip: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val book = requireNotNull(database.bookDao().get(id))
            val root = storage.bookDirectory(id)
            val sourceBytes = AtomicFile(File(book.sourcePath)).openRead().use { it.readBytes() }
            val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入导出文件" }
            output.use { target ->
                if (!zip) target.write(sourceBytes)
                else ZipOutputStream(target).use { z ->
                    z.putNextEntry(ZipEntry(MarkdownText.safeName(book.title) + ".md")); z.write(sourceBytes); z.closeEntry()
                    root.resolve("assets").listFiles()?.filter(File::isFile)?.forEach { file ->
                        z.putNextEntry(ZipEntry("assets/${file.name}")); file.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    }
                }
            }
        }
    }

    companion object {
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun readJson(file: File): JSONObject? = runCatching { JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }.getOrNull()
        private fun atomicWrite(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs(); val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try {
                stream.write(bytes); atomic.finishWrite(stream)
                check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes)) { "文件写入校验失败" }
            } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
}
