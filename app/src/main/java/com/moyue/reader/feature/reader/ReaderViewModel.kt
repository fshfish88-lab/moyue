package com.moyue.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.database.ReadingProgressEntity
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.ReaderPreferences
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
)

class ReaderViewModel(private val repository: ReaderRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<ReaderState?>(null)
    val state: StateFlow<ReaderState?> = mutableState.asStateFlow()
    private var saveJob: Job? = null

    suspend fun open(bookId: Long, chapterIndex: Int? = null) {
        val session = repository.open(bookId, chapterIndex)
        val restored = session.restoredProgress?.takeIf {
            chapterIndex == null && it.chapterId == session.chapter.id
        }
        mutableState.value = ReaderState(
            bookId = bookId,
            bookTitle = session.book.title,
            chapter = session.chapter,
            chapters = session.chapters,
            position = restored?.let { ReaderPosition(it.blockIndex, it.charOffset) } ?: ReaderPosition(0, 0),
            chapterProgress = restored?.chapterProgress ?: 0f,
            bookProgress = restored?.bookProgress ?: chapterBookProgress(session.chapter.index, 0f, session.chapters.size),
            controlsVisible = false,
            preferences = ReaderPreferences(),
            canGoPrevious = session.chapter.index > 0,
            canGoNext = session.chapter.index < session.chapters.lastIndex,
        )
    }

    suspend fun goNext() {
        val index = mutableState.value?.chapter?.index ?: return
        moveTo(index + 1)
    }

    suspend fun goPrevious() {
        val index = mutableState.value?.chapter?.index ?: return
        moveTo(index - 1)
    }
    suspend fun goTo(index: Int) = moveTo(index)

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
        saveJob = viewModelScope.launch {
            delay(300)
            save(updated)
        }
    }

    suspend fun flushProgress() {
        saveJob?.cancel()
        mutableState.value?.let { save(it) }
    }

    private suspend fun moveTo(index: Int) {
        val current = mutableState.value ?: return
        if (index !in current.chapters.indices) return
        save(current)
        val chapter = repository.chapter(index)
        mutableState.value = current.copy(
            chapter = chapter,
            position = ReaderPosition(0, 0),
            chapterProgress = 0f,
            bookProgress = chapterBookProgress(index, 0f, current.chapters.size),
            canGoPrevious = index > 0,
            canGoNext = index < current.chapters.lastIndex,
        )
    }

    private suspend fun save(state: ReaderState) {
        repository.saveProgress(
            ReadingProgressEntity(
                bookId = state.bookId,
                chapterId = state.chapter.id,
                blockIndex = state.position.blockIndex,
                charOffset = state.position.charOffset,
                chapterProgress = state.chapterProgress,
                bookProgress = state.bookProgress,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun chapterBookProgress(index: Int, chapterProgress: Float, count: Int): Float =
        ((index + chapterProgress) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
}
