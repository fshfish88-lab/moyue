package com.moyue.reader.feature.annotations

import androidx.room.withTransaction
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.*
import com.moyue.reader.feature.reader.RoomReaderDataSource
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
        database.bookDao().observeAll().map { list -> list.map { it.id } }.distinctUntilChanged().collectLatest { ids ->
            ids.forEach { id -> database.bookDao().get(id)?.let { indexBook(it) } }
        }
    } }
    fun refresh() { jobs.launch { database.bookDao().observeAll().first().forEach { indexBook(it, force = true) } } }
    fun markdownSaved(id: Long) { jobs.launch { database.bookDao().get(id)?.let { indexBook(it, force = true) } } }
    fun chapterLoaded(bookId: Long, chapter: ReaderChapter) { jobs.launch {
        mutex.withLock { if (database.bookDao().get(bookId) != null) {
            putChapter(bookId, chapter)
            val old = dao.state(bookId)
            if (old?.status == "PARTIAL") {
                val count = database.openHelper.readableDatabase.query("SELECT COUNT(DISTINCT partKey) FROM search_chunks WHERE bookId=?", arrayOf(bookId)).use { it.moveToFirst(); it.getInt(0) }
                dao.state(old.copy(indexedParts = count, status = if (count >= old.totalParts) "READY" else "PARTIAL", message = "已索引缓存正文：$count/${old.totalParts} 章"))
            }
        } }
    } }

    private suspend fun indexBook(book: BookEntity, force: Boolean = false) = mutex.withLock {
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
                dao.state(SearchStateEntity(book.id, fingerprint, "VISUAL", 0, 0, "图片/漫画支持书名与标注搜索")); return@withLock
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
                else -> {
                    val source = RoomReaderDataSource(database, container.storage, webFetcher = container.webPages)
                    val available = rows.filter { book.sourceType != SourceType.WEB || it.cachePath?.let { file -> File(file).isFile } == true }
                    for (row in available.drop(done)) {
                        currentCoroutineContext().ensureActive()
                        val chapter = if (book.sourceType == SourceType.WEB) {
                            val text = File(requireNotNull(row.cachePath)).readText()
                            ReaderChapter(row.id, row.title, row.chapterIndex, text.split(Regex("\\R\\s*\\R")).map(String::trim).filter(String::isNotEmpty).map(ContentBlock::Text))
                        } else source.load(book, row)
                        putChapter(book.id, chapter); done++
                        dao.state(SearchStateEntity(book.id, fingerprint, "INDEXING", done, total, "正在索引：$done/$total 章"))
                        yield()
                    }
                }
            }
            val hasText = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_chunks WHERE bookId=?", arrayOf(book.id)).use { it.moveToFirst(); it.getLong(0) > 0 }
            val state = if (book.sourceType == SourceType.DOCUMENT && !hasText) "SCAN" else if (done < total) "PARTIAL" else "READY"
            dao.state(SearchStateEntity(book.id, fingerprint, state, done, total, when (state) { "SCAN" -> "扫描 PDF 没有文本层"; "PARTIAL" -> "仅搜索已缓存正文：$done/$total 章"; else -> "正文索引完成" }))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (database.bookDao().get(book.id) != null) dao.state(SearchStateEntity(book.id, fingerprint, "ERROR", done, rows.size, "正文索引失败，可重建：${e.message.orEmpty().take(80)}")) }
    }

    private suspend fun putChapter(bookId: Long, chapter: ReaderChapter) {
        val key = "chapter:${chapter.id}"
        database.withTransaction {
            dao.deletePartTerms(bookId, key); dao.deletePart(bookId, key)
            chapter.blocks.forEachIndexed { block, value ->
                val text = blockText(value)
                putChunks(bookId, key, chapter.title, text, JSONObject().put("kind", "TEXT").put("chapterId", chapter.id)
                    .put("chapterIndex", chapter.index).put("blockIndex", block), textHash(text))
            }
        }
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
