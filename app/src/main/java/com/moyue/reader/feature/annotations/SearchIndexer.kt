package com.moyue.reader.feature.annotations

import androidx.room.withTransaction
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class SearchIndexer(private val container: MoyueContainer) {
    private val database get() = container.database
    private val dao get() = database.searchDao()
    private val mutex = Mutex()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start() { jobs.launch {
        dao.prune()
        combine(
            database.bookDao().observeAll().map { books ->
                books.sortedBy { it.id }.map { it.copy(lastReadAt = null, progress = 0f, coverPath = null) }
            }.distinctUntilChanged(),
            database.chapterDao().observeCatalog().map { rows ->
                rows.map { Triple(it.id, it.title, it.chapterIndex) }
            }.distinctUntilChanged(),
        ) { books, _ -> books }.collectLatest { books -> books.forEach { indexBook(it) } }
    } }
    fun refresh(bookId: Long? = null) { jobs.launch {
        val books = if (bookId == null) database.bookDao().observeAll().first() else listOfNotNull(database.bookDao().get(bookId))
        books.forEach { indexBook(it, force = true) }
    } }
    fun markdownSaved(id: Long) { jobs.launch { database.bookDao().get(id)?.let { indexBook(it, force = true) } } }

    private suspend fun indexBook(book: BookEntity, force: Boolean = false) = mutex.withLock {
        if (book.sourceType in setOf(SourceType.TXT, SourceType.EPUB, SourceType.WEB)) {
            val rows = database.chapterDao().forBook(book.id)
            val fingerprint = "catalog-v2:" + textHash(rows.joinToString("\n") { "${it.id}:${it.chapterIndex}:${it.title}" })
            if (!force && dao.state(book.id)?.fingerprint == fingerprint) return@withLock
            // Replacing the old novel index also removes its obsolete FTS terms, atomically.
            // No chapter files, EPUB parser or network requests participate in catalog search.
            database.withTransaction {
                if (database.bookDao().get(book.id) == null) return@withTransaction
                dao.deleteBookTerms(book.id); dao.deleteBook(book.id)
                rows.forEach { row ->
                    putChunks(book.id, "catalog:${row.id}", "目录 · ${row.title}", row.title,
                        JSONObject().put("kind", "TEXT").put("chapterId", row.id)
                            .put("chapterIndex", row.chapterIndex).put("blockIndex", 0), "")
                }
                dao.state(SearchStateEntity(book.id, fingerprint, "READY", rows.size, rows.size, "目录索引完成，不索引小说正文"))
            }
            return@withLock
        }
        val file = File(book.sourcePath)
        val rows = database.chapterDao().forBook(book.id)
        val fingerprint = "${file.length()}:${file.lastModified()}:" + rows.joinToString(";") {
            val cache = it.cachePath?.let(::File)
            "${it.id}:${cache?.length()}:${cache?.lastModified()}"
        }
        val old = dao.state(book.id)
        if (!force && old?.fingerprint == fingerprint && old.status in setOf("READY", "PARTIAL", "SCAN", "LOCKED", "VISUAL")) return@withLock
        var done = if (!force && old?.fingerprint == fingerprint && old.status in setOf("INDEXING", "ERROR")) old.indexedParts else 0
        try {
            val metadata = database.documentDao().metadata(book.id)
            if (book.sourceType == SourceType.DOCUMENT && metadata?.format != "PDF") {
                dao.state(SearchStateEntity(book.id, fingerprint, "VISUAL", 0, 0, "图片/漫画支持名称搜索")); return@withLock
            }
            val total = if (book.sourceType == SourceType.MARKDOWN) 1 else metadata?.pageCount ?: rows.size
            dao.state(SearchStateEntity(book.id, fingerprint, "INDEXING", done, total, "正在建立正文索引"))
            when (book.sourceType) {
                SourceType.MARKDOWN -> {
                    val text = android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val plain = MarkdownTextExtractor.extract(container.applicationContext, text)
                    putPart(book.id, "markdown", "Markdown 正文", plain, JSONObject().put("kind", "MARKDOWN"), textHash(text))
                    done = 1
                }
                SourceType.DOCUMENT -> {
                    if (metadata?.locked == true) {
                        dao.state(SearchStateEntity(book.id, fingerprint, "LOCKED", 0, total, "加密 PDF 请先打开并解锁")); return@withLock
                    }
                    PdfTextExtractor.extract(container.applicationContext, file, startPage = done) { page, text ->
                        putPart(book.id, "pdf:$page", "第 ${page + 1} 页", text, JSONObject().put("kind", "PDF").put("pageIndex", page), textHash(text))
                        done++
                        dao.state(SearchStateEntity(book.id, fingerprint, "INDEXING", done, total, "正在索引 PDF：$done/$total 页"))
                    }
                }
                else -> Unit // Novels are handled by the catalog-only branch above.
            }
            val hasText = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_chunks WHERE bookId=?", arrayOf(book.id)).use { it.moveToFirst(); it.getLong(0) > 0 }
            val state = if (book.sourceType == SourceType.DOCUMENT && !hasText) "SCAN" else if (done < total) "PARTIAL" else "READY"
            dao.state(SearchStateEntity(book.id, fingerprint, state, done, total, when (state) { "SCAN" -> "扫描 PDF 没有文本层"; "PARTIAL" -> "文档正文已索引：$done/$total 部分"; else -> "正文索引完成" }))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (database.bookDao().get(book.id) != null) dao.state(SearchStateEntity(book.id, fingerprint, "ERROR", done, rows.size, "正文索引失败，可重建：${e.message.orEmpty().take(80)}")) }
    }

    suspend fun pdfPage(bookId: Long, page: Int, text: String) = mutex.withLock {
        if (database.bookDao().get(bookId) != null) putPart(bookId, "pdf:$page", "第 ${page + 1} 页", text, JSONObject().put("kind", "PDF").put("pageIndex", page), textHash(text))
    }
    private suspend fun putPart(bookId: Long, key: String, location: String, text: String, anchor: JSONObject, hash: String) {
        database.withTransaction { dao.deletePartTerms(bookId, key); dao.deletePart(bookId, key); putChunks(bookId, key, location, text, anchor, hash) }
    }
    private suspend fun putChunks(bookId: Long, key: String, location: String, text: String, anchor: JSONObject, hash: String) {
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + 4096)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val part = text.substring(start, end)
            if (part.isNotBlank()) {
                val a = JSONObject(anchor.toString()).put("charOffset", start)
                val id = dao.addChunk(SearchChunkEntity(bookId = bookId, partKey = key, location = location, anchorJson = a.toString(), text = part, sourceHash = hash))
                dao.addTerms(SearchFtsEntity(id, SearchTerms.index(part)))
            }
            if (end == text.length) break
            start = end - 128
            if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        }
    }
}
