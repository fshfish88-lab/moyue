package com.moyue.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.ReaderPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReaderState(
    val bookId: Long,
    val bookTitle: String,
    val chapter: ReaderChapter,
    val chapters: List<ChapterEntity>,
    val position: ReaderPosition,
    val chapterProgress: Float,
    val bookProgress: Float,
    val controlsVisible: Boolean,
    val preferences: ReaderPreferences,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
    val error: String? = null,
    val scrollWindow: List<ReaderChapter> = emptyList(),
    val scrollSession: Int = 0,
    val prefetching: Boolean = false,
    val catalogMessage:String?=null,
    val catalogRefreshing:Boolean=false,
)

class ReaderViewModel(private val repository: ReaderRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<ReaderState?>(null)
    val state: StateFlow<ReaderState?> = mutableState.asStateFlow()
    private val mutableOpenError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = mutableOpenError.asStateFlow()
    private val navigation = Mutex()
    private var saveJob: Job? = null

    // True while this session opened somewhere the stored position never was (for example the
    // chapter requested by a failing restore). Passive updates must not move the stored position
    // before that chapter; anything else the reader does stays saveable. Kept as a flag rather than
    // a progress value so it cannot go stale when the chapter count changes mid-session.
    private var restorePending = false

    // Chapter index the stored position belongs to, or null when nothing was stored yet.
    private var storedChapterIndex: Int? = null

    suspend fun open(bookId: Long, chapterIndex: Int? = null) {
        saveJob?.cancel()
        mutableState.value = null
        mutableOpenError.value = null
        restorePending = false
        storedChapterIndex = null
        try {
        val session = repository.open(bookId, chapterIndex)
        // Restore the stored block position whenever the opened chapter is the stored chapter,
        // no matter which entry point (shelf card, detail page, explicit index) asked for it.
        val restored = session.restoredProgress?.takeIf { it.chapterId == session.chapter.id }
        restorePending = restored == null && session.restoredProgress != null
        storedChapterIndex = session.restoredProgress?.let { stored ->
            session.chapters.indexOfFirst { it.id == stored.chapterId }.takeIf { it >= 0 }
        }
        mutableState.value = ReaderState(
            bookId = bookId,
            bookTitle = session.book.title,
            chapter = session.chapter,
            scrollWindow = listOf(session.chapter),
            chapters = session.chapters,
            position = restored?.let { safeReaderPosition(session.chapter.blocks, it.blockIndex, it.charOffset) } ?: ReaderPosition(0, 0),
            chapterProgress = restored?.chapterProgress ?: 0f,
            bookProgress = restored?.bookProgress ?: chapterBookProgress(session.chapter.index, 0f, session.chapters.size),
            controlsVisible = false,
            preferences = ReaderPreferences(),
            canGoPrevious = session.chapter.index > 0,
            canGoNext = session.chapter.index < session.chapters.lastIndex ||
                !session.chapters[session.chapter.index].nextUrl.isNullOrBlank(),
            catalogMessage=session.catalogMessage,
        )
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            mutableOpenError.value = "无法打开这本书：${error.message ?: "章节读取失败"}"
        }
    }

    suspend fun goNext() = navigate { nextChapter() }

    private suspend fun navigate(action: suspend () -> Unit) {
        navigation.lock()
        try {
            saveJob?.cancel()
            action()
            mutableState.value = mutableState.value?.copy(error = null)
            // Navigation is a decision made by the reader, so the chapter it reached is persisted
            // even when it sits earlier in the book: only passive updates are guarded.
            mutableState.value?.let { save(it, regression = true) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value?.copy(error = error.message ?: "章节加载失败，请重试", controlsVisible = true)
        } finally { navigation.unlock() }
    }

    private suspend fun nextChapter() {
        val current = mutableState.value ?: return
        val chapter = repository.next(current.chapter.index) ?: return
        val chapters = repository.chaptersSnapshot()
        // Moving on is a reading decision: later positions are real even if the stored one sits
        // further ahead, so the restore guard no longer applies and is closed before anything can
        // fail below.
        restorePending = false
        storedChapterIndex = null
        save(current)
        mutableState.value = current.copy(
            chapter = chapter,
            scrollWindow = listOf(chapter),
            scrollSession = current.scrollSession + 1,
            chapters = chapters,
            position = ReaderPosition(0, 0),
            chapterProgress = 0f,
            bookProgress = chapterBookProgress(chapter.index, 0f, chapters.size),
            canGoPrevious = true,
            canGoNext = chapter.index < chapters.lastIndex || !chapters[chapter.index].nextUrl.isNullOrBlank(),
        )
    }

    suspend fun goPrevious() {
        val index = mutableState.value?.chapter?.index ?: return
        navigate { moveTo(index - 1) }
    }
    suspend fun goTo(index: Int) = navigate { moveTo(index) }
    suspend fun refreshCatalog() {
        if(!navigation.tryLock())return
        val before=mutableState.value
        try {
            if(before==null)return
            mutableState.value=before.copy(catalogRefreshing=true)
            val (chapters,message)=repository.refreshCatalog()
            val index=chapters.indexOfFirst {it.id==before.chapter.id}
            require(index>=0) {"当前章节未找到，已保留阅读位置"}
            val current=mutableState.value ?: return
            val chapter=current.chapter.copy(index=index)
            mutableState.value=current.copy(chapters=chapters,chapter=chapter,scrollWindow=listOf(chapter),scrollSession=current.scrollSession+1,
                canGoPrevious=index>0,canGoNext=index<chapters.lastIndex || !chapters[index].nextUrl.isNullOrBlank(),catalogMessage=message)
        } catch(error:CancellationException){throw error}
          catch(error:Exception){mutableState.value=mutableState.value?.copy(catalogMessage="目录刷新失败：${error.message}")}
        finally {mutableState.value=mutableState.value?.copy(catalogRefreshing=false);navigation.unlock()}
    }

    suspend fun prefetchFollowing() {
        val before = mutableState.value ?: return
        // Only prepare the next chapter; repeated layout callbacks must not chain through the book.
        if ((before.scrollWindow.lastOrNull()?.index ?: before.chapter.index) > before.chapter.index) return
        if (before.prefetching || !before.preferences.autoNextChapter || !navigation.tryLock()) return
        mutableState.value = before.copy(prefetching = true)
        try {
            val last = before.scrollWindow.lastOrNull() ?: before.chapter
            val chapter = repository.next(last.index) ?: return
            val current = mutableState.value ?: return
            if (current.scrollSession != before.scrollSession) return
            val window = (current.scrollWindow + chapter).distinctBy { it.id }.filter { it.index >= current.chapter.index - 2 }
            mutableState.value = current.copy(scrollWindow = window, chapters = repository.chaptersSnapshot(), error = null)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value?.copy(error = "下一章预加载失败，可点击重试：${error.message}")
        } finally {
            mutableState.value = mutableState.value?.copy(prefetching = false)
            navigation.unlock()
        }
    }

    fun updateScrollPosition(chapterId: Long, position: ReaderPosition, progress: Float) {
        val current = mutableState.value ?: return
        val chapter = current.scrollWindow.firstOrNull { it.id == chapterId } ?: return
        mutableState.value = current.copy(chapter = chapter,
            canGoPrevious = chapter.index > 0,
            canGoNext = chapter.index < current.chapters.lastIndex || !current.chapters[chapter.index].nextUrl.isNullOrBlank())
        updatePosition(position, progress)
    }

    fun toggleControls() {
        mutableState.value = mutableState.value?.let { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun updatePreferences(preferences: ReaderPreferences) {
        mutableState.value = mutableState.value?.copy(preferences = preferences)
    }

    fun updatePosition(position: ReaderPosition, chapterProgress: Float) {
        val current = mutableState.value ?: return
        val safeProgress = chapterProgress.coerceIn(0f, 1f)
        val updated = current.copy(
            position = position,
            chapterProgress = safeProgress,
            bookProgress = chapterBookProgress(current.chapter.index, safeProgress, current.chapters.size),
        )
        mutableState.value = updated
        saveJob?.cancel()
        // The state update above is what the reader sees; the delayed write is best effort, so a
        // scope that cannot start (for example without a main dispatcher) must not undo it.
        saveJob = try {
            viewModelScope.launch {
                delay(300)
                saveSafely(updated)
            }
        } catch (_: IllegalStateException) {
            null
        }
    }

    suspend fun flushProgress() {
        saveJob?.cancel()
        mutableState.value?.let { saveSafely(it) }
    }

    /**
     * Persist the current position when the reader leaves the reader screen.
     * Unlike [flushProgress] this may move the saved position backwards, because backing out of a
     * chapter the reader explicitly opened is a deliberate change of position.
     */
    suspend fun flushProgressOnBack() {
        saveJob?.cancel()
        mutableState.value?.let { saveSafely(it, regression = true) }
    }

    private suspend fun moveTo(index: Int) {
        val current = mutableState.value ?: return
        if (index !in current.chapters.indices) return
        // The reader picked this chapter, so from here on every position in it is a real reading
        // position and the restore guard must stop protecting the older stored one. Clearing it
        // before the write below keeps the new chapter usable even if that write fails.
        restorePending = false
        storedChapterIndex = null
        // The reader chose this chapter, so an earlier chapter may legitimately move progress back.
        save(current, regression = true)
        val chapter = repository.chapter(index)
        mutableState.value = current.copy(
            chapter = chapter,
            scrollWindow = listOf(chapter),
            scrollSession = current.scrollSession + 1,
            position = ReaderPosition(0, 0),
            chapterProgress = 0f,
            bookProgress = chapterBookProgress(index, 0f, current.chapters.size),
            canGoPrevious = index > 0,
            canGoNext = index < current.chapters.lastIndex || !current.chapters[index].nextUrl.isNullOrBlank(),
        )
    }

    private suspend fun save(state: ReaderState, regression: Boolean = false) {
        val progress = chapterBookProgress(state.chapter.index, state.chapterProgress, state.chapters.size)
        // While the restore is still pending, a passive update that sits before the stored chapter
        // is a restore miss rather than a reading decision: keep the stored progress instead of
        // destroying it. Positions inside or after that chapter are real reading positions, so they
        // are saved even when they move the book backwards.
        val storedIndex = storedChapterIndex
        if (!regression && restorePending && storedIndex != null && state.chapter.index < storedIndex) return
        repository.saveProgress(
            ReadingProgressEntity(
                bookId = state.bookId,
                chapterId = state.chapter.id,
                blockIndex = state.position.blockIndex,
                charOffset = state.position.charOffset,
                chapterProgress = state.chapterProgress,
                bookProgress = progress,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun saveSafely(state: ReaderState, regression: Boolean = false) {
        try { save(state, regression) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            mutableState.value = mutableState.value?.copy(error = "阅读进度暂未保存，请检查存储空间")
        }
    }

    private fun chapterBookProgress(index: Int, chapterProgress: Float, count: Int): Float =
        ((index + chapterProgress) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
}
