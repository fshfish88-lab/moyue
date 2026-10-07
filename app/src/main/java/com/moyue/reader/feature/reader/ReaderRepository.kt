package com.moyue.reader.feature.reader

import androidx.room.withTransaction
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.MoyueDatabase
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.storage.BookStorage
import com.moyue.reader.parser.epub.EpubBookParser
import com.moyue.reader.parser.txt.TxtChapterLoader
import com.moyue.reader.parser.web.WebBookParser
import com.moyue.reader.parser.web.ReadabilityExtractor
import com.moyue.reader.parser.web.WebContentFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

interface ReaderDataSource {
    suspend fun book(bookId: Long): BookEntity?
    suspend fun chapters(bookId: Long): List<ChapterEntity>
    suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter
    suspend fun progress(bookId: Long): ReadingProgressEntity?
    suspend fun saveProgress(progress: ReadingProgressEntity)
    suspend fun fetchNext(book: BookEntity, current: ChapterEntity): ChapterEntity? = null
    suspend fun refreshCatalog(bookId:Long):List<ChapterEntity> = chapters(bookId)
    suspend fun catalogMessage(bookId:Long):String? = null
}

data class ReaderSession(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val chapter: ReaderChapter,
    val restoredProgress: ReadingProgressEntity?,
    val catalogMessage:String?=null,
)

class ReaderRepository(private val source: ReaderDataSource) {
    private val cache = linkedMapOf<Int, ReaderChapter>()
    private var currentBookId: Long? = null
    private var currentBook: BookEntity? = null
    private var chapterList: List<ChapterEntity> = emptyList()

    suspend fun open(bookId: Long, requestedIndex: Int? = null): ReaderSession {
        if (currentBookId != bookId) cache.clear()
        var book = requireNotNull(source.book(bookId)) { "找不到书籍" }
        val chapters = source.chapters(bookId)
        book = source.book(bookId) ?: book
        require(chapters.isNotEmpty()) { "书籍没有可阅读章节" }
        val progress = source.progress(bookId)
        val restoredIndex = progress?.let { stored ->
            chapters.indexOfFirst { it.id == stored.chapterId }.takeIf { it >= 0 }
        }
        val importedIndex = chapters.indexOfFirst { it.sourceUrl == book.sourceUrl }.coerceAtLeast(0)
        val index = (requestedIndex ?: restoredIndex ?: importedIndex).coerceIn(chapters.indices)
        currentBookId = bookId
        currentBook = book
        chapterList = chapters
        warm(index)
        return ReaderSession(book, chapters, requireNotNull(cache[index]), progress,source.catalogMessage(bookId))
    }

    suspend fun chapter(index: Int): ReaderChapter {
        require(index in chapterList.indices) { "章节超出范围" }
        warm(index)
        return requireNotNull(cache[index])
    }

    suspend fun next(currentIndex: Int): ReaderChapter? {
        val nextIndex = currentIndex + 1
        if (nextIndex !in chapterList.indices) {
            val current = chapterList.getOrNull(currentIndex) ?: return null
            val fetched = source.fetchNext(requireNotNull(currentBook), current) ?: return null
            chapterList = (chapterList + fetched).sortedBy(ChapterEntity::chapterIndex)
        }
        warm(nextIndex)
        return cache[nextIndex]
    }

    fun chaptersSnapshot(): List<ChapterEntity> = chapterList
    suspend fun refreshCatalog():Pair<List<ChapterEntity>,String?> {
        val id=requireNotNull(currentBookId)
        val updated=source.refreshCatalog(id)
        require(updated.isNotEmpty()) {"没有识别到章节，请确认目录网页可访问"}
        chapterList=updated;cache.clear();currentBook=source.book(id) ?: currentBook
        return updated to source.catalogMessage(id)
    }

    suspend fun saveProgress(progress: ReadingProgressEntity) = source.saveProgress(progress)

    fun cachedChapterIndexes(): Set<Int> = cache.keys.toSet()

    private suspend fun warm(index: Int) {
        val book = requireNotNull(currentBook)
        val keep = setOf(index - 1, index, index + 1).filter { it in chapterList.indices }.toSet()
        if (index !in cache) cache[index] = source.load(book, chapterList[index])
        (if (book.sourceType == SourceType.WEB) emptySet() else keep - index).forEach { chapterIndex ->
            if (chapterIndex !in cache) {
                try { cache[chapterIndex] = source.load(book, chapterList[chapterIndex]) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { /* Optional adjacent content cannot prevent opening the requested chapter. */ }
            }
        }
        cache.keys.toList().filterNot(keep::contains).forEach(cache::remove)
    }
}

class RoomReaderDataSource(
    private val database: MoyueDatabase,
    private val storage: BookStorage,
    private val txtLoader: TxtChapterLoader = TxtChapterLoader(),
    private val webFetcher: WebContentFetcher = WebContentFetcher(),
    private val webExtractor: ReadabilityExtractor = ReadabilityExtractor(),
) : ReaderDataSource {
    override suspend fun book(bookId: Long) = database.bookDao().get(bookId)
    override suspend fun refreshCatalog(bookId:Long):List<ChapterEntity> = withContext(Dispatchers.IO) {
        val book=requireNotNull(database.bookDao().get(bookId))
        val rows=database.chapterDao().forBook(bookId)
        if(book.sourceType==SourceType.WEB)refreshWebCatalog(book,rows,force=true) else rows
    }
    override suspend fun catalogMessage(bookId:Long):String? = withContext(Dispatchers.IO) {
        if(database.bookDao().get(bookId)?.sourceType!=SourceType.WEB)return@withContext null
        val file=storage.webCache(bookId).resolve("catalog-v8.status")
        if(file.isFile)file.readText() else "目录可能未完整，可点击刷新目录重试"
    }
    override suspend fun chapters(bookId: Long): List<ChapterEntity> = withContext(Dispatchers.IO) {
        val rows = database.chapterDao().forBook(bookId)
        val book = database.bookDao().get(bookId)
        if (book?.sourceType == SourceType.WEB) return@withContext refreshWebCatalog(book, rows)
        if (book?.sourceType != SourceType.EPUB) return@withContext rows
        val root = storage.epubCache(bookId)
        val marker = root.resolve("titles-v3.done")
        if (marker.exists()) return@withContext rows
        runCatching {
            if (!root.resolve("unpacked/META-INF/container.xml").isFile) rebuildDerivedCache(book)
            val parser = com.moyue.reader.parser.epub.EpubPackageParser()
            val pkg = parser.parse(root.resolve("unpacked"))
            val updated = rows.map { chapter ->
                val item = pkg.spine.getOrNull(chapter.chapterIndex) ?: return@map chapter
                val file = parser.safeResolve(pkg.packageDirectory, item.href)
                val title = pkg.chapterTitles[file.canonicalPath].orEmpty().ifBlank {
                    com.moyue.reader.parser.epub.HtmlTextNormalizer.title(file.readText())
                }
                if (title.isBlank() || title == chapter.title) chapter else chapter.copy(title = title).also { database.chapterDao().update(it) }
            }
            marker.writeText("3")
            updated
        }.getOrElse { rows }
    }
    private suspend fun refreshWebCatalog(book: BookEntity, rows: List<ChapterEntity>,force:Boolean=false): List<ChapterEntity> {
        val marker = storage.webCache(book.id).resolve("catalog-v8.done")
        val status=storage.webCache(book.id).resolve("catalog-v8.status")
        fun message(text:String){status.parentFile?.mkdirs();status.writeText(text)}
        if (marker.exists() && !force) return rows
        if(force)marker.delete()
        return try {
            val parser = com.moyue.reader.parser.web.WebCatalogExtractor()
            val originalUrl=book.sourceUrl ?: return rows
            val recovered=storage.webCache(book.id).resolve("source-recovered.html")
            val recoveredUrl=recovered.resolveSibling("source-recovered.url")
            var page=com.moyue.reader.parser.web.FetchedWebPage(if(recoveredUrl.isFile)recoveredUrl.readText() else originalUrl,
                (if(recovered.isFile)recovered else File(book.sourcePath)).readText())
            if(force)page=webFetcher.fetch(page.finalUrl)
            if('\uFFFD' in page.html || '\uFFFD' in book.title || rows.any {'\uFFFD' in it.title}) {
                val fetched=webFetcher.fetch(page.finalUrl)
                if('\uFFFD' !in fetched.html) {
                    recovered.parentFile?.mkdirs()
                    recovered.writeText(fetched.html);recoveredUrl.writeText(fetched.finalUrl);page=fetched
                }
            }
            val readable=runCatching {webExtractor.extract(page.html,page.finalUrl)}.getOrNull()
            if(readable!=null && '\uFFFD' !in readable.title && '\uFFFD' in book.title) {
                val latest=database.bookDao().get(book.id) ?: return rows
                database.bookDao().update(latest.copy(title=readable.title))
            }
            val result=com.moyue.reader.parser.web.WebCatalogLoader(webFetcher::fetch,parser).load(page)
            if(result.entries.isEmpty()) {
                message("没有找到完整章节列表，已保留原 ${rows.size} 章；可刷新目录重试")
                if(readable!=null && rows.size==1 && '\uFFFD' in rows[0].title && '\uFFFD' !in readable.title)
                    database.chapterDao().update(rows[0].copy(title=readable.title,sourceUrl=page.finalUrl))
                return database.chapterDao().forBook(book.id)
            }
            if(rows.any {it.sourceUrl.isNullOrBlank()})return rows
            fun identity(url:String?):String? {
                if(url==null)return null
                if(url==originalUrl)return page.finalUrl
                val old=java.net.URI(url)
                if(old.host==java.net.URI(originalUrl).host && page.finalUrl!=originalUrl) {
                    val new=java.net.URI(page.finalUrl)
                    return java.net.URI(new.scheme,old.userInfo,new.host,new.port,old.path,old.query,null).toString()
                }
                return url.substringBefore('#')
            }
            val placeholders=rows.filter {rows.size==1 && identity(it.sourceUrl)==result.directoryUrl && !parser.isChapterTitle(it.title)}
            val retained=rows.filter {it !in placeholders && result.entries.none {entry->entry.url==identity(it.sourceUrl)}}
            val catalog=parser.order(result.entries+retained.mapNotNull {r->identity(r.sourceUrl)?.let {com.moyue.reader.parser.web.WebCatalogEntry(r.title,it)}})
            database.withTransaction {
                rows.forEachIndexed { i, row -> database.chapterDao().update(row.copy(chapterIndex = -i - 1)) }
                catalog.forEachIndexed { index, entry ->
                    val old = rows.firstOrNull { identity(it.sourceUrl) == entry.url && it !in placeholders } ?: placeholders.singleOrNull().takeIf {index==0}
                    val cache=old?.cachePath?.takeIf {old !in placeholders && '\uFFFD' !in old!!.title}
                    val draft = ChapterEntity(id = old?.id ?: 0, bookId = book.id, title = entry.title, chapterIndex = index,
                        startByte = null, endByte = null, cachePath = cache,
                        sourceUrl = entry.url, previousUrl = catalog.getOrNull(index - 1)?.url, nextUrl = catalog.getOrNull(index + 1)?.url)
                    if (old == null) database.chapterDao().insertAll(listOf(draft)) else database.chapterDao().update(draft)
                }
                val latest=requireNotNull(database.bookDao().get(book.id))
                database.bookDao().update(latest.copy(chapterCount = catalog.size,sourceUrl=page.finalUrl))
            }
            marker.parentFile?.mkdirs()
            if (result.complete) marker.writeText("complete")
            message(if(result.complete)"已识别 ${catalog.size} 章；可刷新获取更新" else "目录可能未完整：已识别 ${catalog.size} 章，可刷新重试")
            database.chapterDao().forBook(book.id)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error
        } catch (_: Exception) {message("目录刷新失败，请确认网页可以正常访问；已保留原 ${rows.size} 章"); rows }
    }

    override suspend fun progress(bookId: Long) = database.readingProgressDao().get(bookId)

    override suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter =
        when (book.sourceType) {
            SourceType.TXT -> txtLoader.load(
                File(book.sourcePath),
                chapter,
                Charset.forName(book.textEncoding ?: "UTF-8"),
            )
            SourceType.EPUB -> withContext(Dispatchers.IO) {
                val root = storage.epubCache(book.id).resolve("unpacked")
                if (!root.resolve("META-INF/container.xml").isFile) rebuildDerivedCache(book)
                val parser = com.moyue.reader.parser.epub.EpubPackageParser()
                val pkg = parser.parse(root)
                val item = pkg.spine[chapter.chapterIndex]
                val document = parser.safeResolve(pkg.packageDirectory, item.href)
                val blocks = com.moyue.reader.parser.epub.EpubContentParser.parse(document.readText(), document, root)
                ReaderChapter(chapter.id, chapter.title, chapter.chapterIndex, blocks)
            }
            SourceType.WEB -> loadNormalized(book, chapter)
            SourceType.MARKDOWN, SourceType.DOCUMENT -> error("请使用对应的文档阅读器")
        }

    override suspend fun saveProgress(progress: ReadingProgressEntity) {
        database.readingProgressDao().upsert(progress)
        database.bookDao().get(progress.bookId)?.let { book ->
            database.bookDao().update(
                book.copy(lastReadAt = progress.updatedAt, progress = progress.bookProgress),
            )
        }
    }

    override suspend fun fetchNext(book: BookEntity, current: ChapterEntity): ChapterEntity? {
        if (book.sourceType != SourceType.WEB) return null
        val url = current.nextUrl ?: return null
        require(database.chapterDao().forBook(book.id).none { it.sourceUrl == url }) { "下一章链接重复，请检查网页目录" }
        val fetched = webFetcher.fetch(url)
        val page = webExtractor.extract(fetched.html, fetched.finalUrl)
        require(database.chapterDao().forBook(book.id).none { it.sourceUrl == page.sourceUrl }) { "网页跳回了已读章节" }
        val index = current.chapterIndex + 1
        val cacheFile = storage.webCache(book.id).resolve("chapter-${index.toString().padStart(5, '0')}.txt")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(page.text)
        val draft = ChapterEntity(
            bookId = book.id,
            title = page.title,
            chapterIndex = index,
            startByte = null,
            endByte = null,
            cachePath = cacheFile.absolutePath,
            sourceUrl = page.sourceUrl,
            previousUrl = page.previousUrl,
            nextUrl = page.nextUrl,
        )
        val id = database.chapterDao().insertAll(listOf(draft)).single()
        database.bookDao().update(book.copy(chapterCount = maxOf(book.chapterCount, index + 1)))
        return draft.copy(id = id)
    }

    private suspend fun loadNormalized(book: BookEntity, chapter: ChapterEntity): ReaderChapter =
        withContext(Dispatchers.IO) {
            val file = if (book.sourceType == SourceType.WEB) {
                chapter.cachePath?.let(::File) ?: storage.webCache(book.id).resolve("catalog-${chapter.id}.txt")
            } else File(requireNotNull(chapter.cachePath) { "章节缓存不存在" })
            var title=chapter.title
            if (book.sourceType == SourceType.WEB && (!file.isFile || '\uFFFD' in file.readText() || '\uFFFD' in title)) {
                val page = webFetcher.fetch(requireNotNull(chapter.sourceUrl))
                val readable = webExtractor.extract(page.html, page.finalUrl)
                file.parentFile?.mkdirs()
                require('\uFFFD' !in readable.text) { "网页正文仍存在编码错误，请在网页浏览中确认后重试" }
                val temp=file.resolveSibling(file.name+".tmp")
                try {temp.writeText(readable.text);java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)} finally {temp.delete()}
                title=readable.title
                database.chapterDao().update(chapter.copy(title=title,cachePath = file.absolutePath))
            } else if (!file.isFile) rebuildDerivedCache(book)
            require(file.isFile) { "章节缓存重建失败" }
            val blocks = file.readText().split(Regex("\\R\\s*\\R"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(ContentBlock::Text)
            ReaderChapter(chapter.id, title, chapter.chapterIndex, blocks)
        }

    private suspend fun rebuildDerivedCache(book: BookEntity) {
        val source = File(book.sourcePath)
        val parsed = when (book.sourceType) {
            SourceType.EPUB -> EpubBookParser().parse(ParseInput(source, SourceType.EPUB))
            SourceType.WEB -> WebBookParser().parse(ParseInput(source, SourceType.WEB, book.sourceUrl))
            SourceType.TXT, SourceType.MARKDOWN, SourceType.DOCUMENT -> return
        }
        check(parsed.chapters.isNotEmpty())
        val staged = requireNotNull(source.parentFile).resolve("derived/${book.sourceType.name.lowercase()}")
        val destination = when (book.sourceType) {
            SourceType.EPUB -> storage.epubCache(book.id)
            SourceType.WEB -> storage.webCache(book.id)
            SourceType.TXT, SourceType.MARKDOWN, SourceType.DOCUMENT -> return
        }
        if (destination.exists()) destination.deleteRecursively()
        destination.parentFile?.mkdirs()
        check(staged.renameTo(destination)) { "无法重建章节缓存" }
    }
}
