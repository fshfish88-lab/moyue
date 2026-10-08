package com.moyue.reader.feature.annotations

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.room.withTransaction
import com.moyue.reader.*
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/** Deliberately missing novel source files prove catalog indexing never parses novel bodies. */
class SearchScopeInstrumentation : Instrumentation() {
    private var count = 0
    private fun verify(ok: Boolean, label: String) {
        check(ok) { label }; count++
        sendStatus(1, Bundle().apply { putString("stream", "PASS $count: $label") })
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo) { add(n); for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitNode(text: String): AccessibilityNodeInfo {
        repeat(100) {
            nodes().firstOrNull { it.text?.toString() == text }?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing search UI: $text")
    }
    override fun onStart() {
        val result = Bundle(); var success = false
        lateinit var c: MoyueContainer
        var initialized = false
        val ids = mutableListOf<Long>()
        val files = mutableListOf<File>()
        try {
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            initialized = true
            val chapters = mutableMapOf<Long, Long>()
            var markdownId = 0L; var pdfId = 0L
            runBlocking {
                c.database.withTransaction {
                    for (type in listOf(SourceType.TXT, SourceType.EPUB, SourceType.WEB, SourceType.DOCUMENT)) {
                        val book = BookEntity(title = "V163名称${type.name}", author = null, sourceType = type,
                            sourcePath = File(targetContext.cacheDir, "never-read-${type.name}").path,
                            textEncoding = null, sourceUrl = null, coverPath = null, chapterCount = 1, wordCount = 0,
                            createdAt = System.currentTimeMillis(), lastReadAt = null, progress = 0f)
                        val id = c.database.bookDao().insert(book); ids += id
                        if (type == SourceType.DOCUMENT) {
                            pdfId = id
                            c.database.documentDao().upsertMetadata(DocumentMetadataEntity(id, "PDF", "application/pdf", 0, 1, true))
                        } else {
                            chapters[id] = c.database.chapterDao().insertAll(listOf(ChapterEntity(bookId = id,
                                title = "第一章 目录独有星河${type.name}", chapterIndex = 0, startByte = null, endByte = null,
                                cachePath = null, sourceUrl = null, previousUrl = null, nextUrl = null))).single()
                        }
                    }
                }
                withTimeout(30000) { while (chapters.keys.any { c.database.searchDao().state(it)?.status != "READY" }) delay(100) }
                val dao = c.database.searchDao()
                val catalog = dao.search(SearchTerms.query("目录独有星河"), "目录独有星河", 50, 0)
                verify(chapters.keys.all { id -> catalog.any { it.chunk.bookId == id } }, "TXT EPUB and uncached WEB catalogs searchable without source files")
                verify(catalog.filter { it.chunk.bookId in chapters }.all { it.chunk.partKey.startsWith("catalog:") }, "novel indexes contain catalog entries only")
                val novelId = chapters.keys.first()
                val legacyId = dao.addChunk(SearchChunkEntity(bookId = novelId, partKey = "chapter:legacy", location = "旧正文",
                    anchorJson = "{}", text = "小说正文独有暗号", sourceHash = ""))
                dao.addTerms(SearchFtsEntity(legacyId, SearchTerms.index("小说正文独有暗号")))
                verify(dao.search(SearchTerms.query("小说正文独有暗号"), "小说正文独有暗号", 50, 0).isEmpty(), "legacy novel body hidden immediately even before cleanup")
                val state = checkNotNull(dao.state(novelId)); dao.state(state.copy(fingerprint = "legacy"))
                c.searchIndexer.refresh(novelId)
                withTimeout(20000) { while (dao.state(novelId)?.fingerprint == "legacy") delay(50) }
                val oldChunks = c.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_chunks WHERE id=?", arrayOf(legacyId)).use { it.moveToFirst(); it.getInt(0) }
                val oldTerms = c.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM search_fts WHERE rowid=?", arrayOf(legacyId)).use { it.moveToFirst(); it.getInt(0) }
                verify(oldChunks == 0 && oldTerms == 0, "rebuild removes legacy novel chunks and their FTS terms")
                val chapter = checkNotNull(c.database.chapterDao().get(chapters.getValue(novelId)))
                c.database.chapterDao().update(chapter.copy(title = "第一章 新目录自动同步"))
                withTimeout(20000) { while (dao.search(SearchTerms.query("新目录自动同步"), "新目录自动同步", 50, 0).none { it.chunk.bookId == novelId }) delay(100) }
                verify(dao.search(SearchTerms.query("新目录自动同步"), "新目录自动同步", 50, 0, "BODY").isEmpty(), "catalog updates are observed and excluded from document body tab")
                val md = File(targetContext.cacheDir, "V163文档名称.md").apply { writeText("# 文档标题\n\n文档正文独有星辰，格式**保留**。\n") }; files += md
                markdownId = (c.importService.importDocument(android.net.Uri.fromFile(md), md.name) as ImportState.Completed).bookId; ids += markdownId
                withTimeout(30000) { while (dao.search(SearchTerms.query("文档正文独有星辰"), "文档正文独有星辰", 50, 0, "BODY").none { it.chunk.bookId == markdownId }) delay(100) }
                verify(dao.search(SearchTerms.query("文档正文独有星辰"), "文档正文独有星辰", 50, 0, "CATALOG").isEmpty(), "Markdown document body remains searchable only in the correct scopes")
                c.searchIndexer.pdfPage(pdfId, 0, "PDF文档正文独有海洋")
                verify(dao.search(SearchTerms.query("独有海洋"), "独有海洋", 50, 0, "BODY").any { it.chunk.bookId == pdfId }, "PDF extracted page text remains searchable")
                val countBefore = dao.search(SearchTerms.query("新目录自动同步"), "新目录自动同步", 50, 0).single { it.chunk.bookId == novelId }.chunk.id
                c.database.bookDao().updateReading(novelId, .3f, System.currentTimeMillis()); delay(600)
                verify(dao.search(SearchTerms.query("新目录自动同步"), "新目录自动同步", 50, 0).single { it.chunk.bookId == novelId }.chunk.id == countBefore, "reading progress does not rebuild catalog indexes")
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val opened = java.util.concurrent.atomic.AtomicReference<Pair<Long, String?>?>(null)
            runOnMainSync { activity.setContent { MaterialTheme { GlobalSearch(c, {}, { id, anchor -> opened.set(id to anchor) }) } } }
            waitNode("全局搜索")
            fun query(value: String) {
                check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
            }
            query("V163名称"); waitNode("V163名称TXT"); waitNode("V163名称EPUB"); waitNode("V163名称WEB"); waitNode("V163名称DOCUMENT")
            verify(true, "global search UI includes every novel and document name")
            query("V163文档名称"); waitNode("V163文档名称"); verify(true, "Markdown document name appears independently of body indexing")
            query("新目录自动同步"); val catalogNode = waitNode("V163名称TXT · 目录 · 第一章 新目录自动同步")
            var clickable: AccessibilityNodeInfo? = catalogNode
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            SystemClock.sleep(150)
            val anchor = JSONObject(checkNotNull(opened.get()?.second))
            verify(anchor.optLong("chapterId") == chapters.getValue(chapters.keys.first()) && anchor.optString("quote").isEmpty(), "catalog UI opens chapter start without a false body-text anchor")
            query("文档正文独有星辰"); waitNode("V163文档名称 · Markdown 正文")
            verify(true, "document body result remains visible in global search UI")
            result.putString("stream", "PASS search-scope checks=$count"); success = true
        } catch (error: Throwable) { result.putString("stream", "FAIL ${error.stackTraceToString()}") }
        finally {
            if (initialized) runBlocking {
                ids.forEach { id -> c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }; c.storage.bookDirectory(id).deleteRecursively() }
                c.database.searchDao().prune()
            }
            files.forEach { it.delete() }
        }
        finish(if (success) -1 else 0, result)
    }
}
