package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.*
import com.moyue.reader.feature.annotations.textHash
import com.moyue.reader.feature.annotations.annotationColor
import kotlinx.coroutines.*
import org.json.JSONObject

/** Real Main dispatcher / debounce and JSON checks, using an isolated in-memory source. */
class ReaderOptimizationInstrumentation : Instrumentation() {
    private var checks = 0
    private fun verify(ok: Boolean, label: String) {
        check(ok) { label }
        checks++
        sendStatus(1, Bundle().apply { putString("stream", "PASS $checks: $label") })
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    val source = FixtureSource()
                    val model = ReaderViewModel(ReaderRepository(source))
                    try {
                        model.open(1)
                        val initial = model.state.value
                        repeat(1000) { model.updateScrollPosition(1, ReaderPosition(0, 0), 0f) }
                        verify(model.state.value === initial && source.writes.isEmpty(), "1000 duplicate positions create no state or save")
                        model.updatePosition(ReaderPosition(0, 1), .1f)
                        val worker = model.viewModelScope.coroutineContext[Job]!!.children.single()
                        repeat(20) { i ->
                            model.updatePosition(ReaderPosition(0, i + 2), .2f)
                            verify(model.viewModelScope.coroutineContext[Job]!!.children.single() === worker, "burst reuses worker $i")
                            delay(10)
                        }
                        verify(source.writes.isEmpty(), "debounce does not save while scrolling")
                        delay(450)
                        verify(source.writes.size == 1 && source.writes.single().charOffset == 21, "debounce saves latest position once")
                        model.updatePosition(ReaderPosition(0, 22), .3f)
                        model.flushProgressOnBack()
                        verify(source.writes.size == 2 && source.writes.last().charOffset == 22, "back immediately flushes latest position")
                        delay(400)
                        verify(source.writes.size == 2, "flushed worker cannot save a second time")
                        model.updatePosition(ReaderPosition(0, 23), .4f)
                        model.goTo(2)
                        delay(400)
                        verify(source.writes.last().chapterId == 3L && source.writes.last().charOffset == 0, "navigation cancels old deferred position")
                    } finally { model.viewModelScope.cancel() }
                    val cancellable = FixtureSource().apply { slowAdjacent = true }
                    val model2 = ReaderViewModel(ReaderRepository(cancellable))
                    try {
                        model2.open(1)
                        val prefetch = launch { model2.prefetchAdjacent() }
                        cancellable.started.await()
                        withTimeout(2000) { model2.goTo(2) }
                        verify(prefetch.isCancelled && model2.state.value!!.chapter.index == 2, "navigation cancels optional adjacent load")
                    } finally { model2.viewModelScope.cancel() }
                }
            }
            checkHighlights()
            result.putString("stream", "PASS reader optimization checks=$checks")
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL ${error.stackTraceToString()}")
            finish(0, result)
        }
    }

    private fun checkHighlights() {
        val chapter = ReaderChapter(1, "章", 0, listOf(ContentBlock.Text("abcdefghij"), ContentBlock.Text("klmnopqrst")))
        fun mark(id: Long, first: Int, last: Int, start: Int, end: Int, color: String, time: Long = id) = AnnotationEntity(
            id, 1, "HIGHLIGHT", JSONObject().put("kind", "TEXT").put("chapterId", 1).put("blockIndex", first)
                .put("endBlock", last).put("charOffset", start).put("endOffset", end).toString(),
            "text", "", color, "章", textHash(if (first == 0) "abcdefghij" else "klmnopqrst"), 0, time,
        )
        val first = mark(1, 0, 1, 2, 4, "green")
        val newer = mark(2, 0, 0, 4, 7, "blue")
        val transient = mark(-1, 0, 0, 5, 6, "yellow", 0)
        val invalid = newer.copy(id = 3, sourceHash = "changed")
        val malformed = newer.copy(id = 4, anchorJson = "invalid json")
        val bookmark = newer.copy(id = 5, type = "BOOKMARK")
        val other = newer.copy(id = 6, anchorJson = JSONObject(newer.anchorJson).put("chapterId", 2).toString())
        val indexed = indexReaderAnnotations(listOf(transient, newer, first, invalid, malformed, bookmark, other))
        val ranges = chapterHighlights(chapter, indexed[1].orEmpty())
        fun color(block: Int, offset: Int) = ranges[block].orEmpty().singleOrNull { offset in it.start until it.end }?.color
        verify(color(0, 1) == null && color(0, 2) == annotationColor("green"), "first paragraph range and source offsets")
        verify(color(1, 0) == annotationColor("green") && color(1, 4) == null, "multiline mark reaches only requested end offset")
        verify(color(0, 4) == annotationColor("blue") && color(0, 7) == annotationColor("green"), "latest color preserves older uncovered ranges")
        verify(color(0, 5) == annotationColor("yellow"), "temporary jump highlight is ordered last")
        verify(indexed[1]!!.size == 4 && indexed[2]!!.size == 1, "invalid JSON and bookmarks excluded; chapters indexed separately")
        verify(chapterHighlights(chapter.copy(blocks = listOf(ContentBlock.Text("changed"))), indexed[1].orEmpty()).isEmpty(), "changed source hashes suppress stale marks")
    }

    private class FixtureSource : ReaderDataSource {
        val writes = mutableListOf<ReadingProgressEntity>()
        var slowAdjacent = false
        val started = CompletableDeferred<Unit>()
        private val rows = List(3) { ChapterEntity((it + 1).toLong(), 1, "章$it", it, null, null, null, null, null, null) }
        override suspend fun book(bookId: Long) = BookEntity(1, "测试", null, SourceType.TXT, "test.txt", "UTF-8", null, null, 3, 30, 0, null, 0f)
        override suspend fun chapters(bookId: Long) = rows
        override suspend fun load(book: BookEntity, chapter: ChapterEntity): ReaderChapter {
            if (slowAdjacent && chapter.chapterIndex == 1) { started.complete(Unit); awaitCancellation() }
            return ReaderChapter(chapter.id, chapter.title, chapter.chapterIndex, listOf(ContentBlock.Text("abcdefghij")))
        }
        override suspend fun progress(bookId: Long): ReadingProgressEntity? = null
        override suspend fun saveProgress(progress: ReadingProgressEntity) { writes += progress }
    }
}
