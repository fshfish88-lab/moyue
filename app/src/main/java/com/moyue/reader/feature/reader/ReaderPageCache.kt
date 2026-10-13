package com.moyue.reader.feature.reader

import android.graphics.Typeface
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.settings.ReaderPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reader-session LRU. Immutable chapter lists are compared by identity to avoid scanning text. */
internal class ReaderPageCache {
    private class ContentIdentity(val blocks: List<ContentBlock>) {
        override fun equals(other: Any?) = other is ContentIdentity && blocks === other.blocks
        override fun hashCode() = System.identityHashCode(blocks)
    }
    private data class Key(val content: ContentIdentity, val size: Float, val font: Typeface,
        val line: Any, val paragraph: Any, val indent: Boolean, val width: Int, val height: Int)
    private val entries = LinkedHashMap<Key, List<ReaderPage>>(4, .75f, true)
    private val mutex = Mutex()
    suspend fun pages(blocks: List<ContentBlock>, p: ReaderPreferences, width: Int, height: Int, fontPx: Float, face: Typeface): List<ReaderPage> = mutex.withLock {
        val key = Key(ContentIdentity(blocks), fontPx, face, p.lineSpacing, p.paragraphSpacing, p.indentParagraphs, width, height)
        entries[key]?.let { return@withLock it }
        val pages = NativePageLayout.create(blocks, p, width, height, fontPx, face)
        if (pages.sumOf { it.text.length.toLong() } <= 1_000_000) {
            entries[key] = pages
            while (entries.size > 3 || entries.values.sumOf { list -> list.sumOf { it.text.length.toLong() } } > 2_000_000) {
                entries.remove(entries.keys.first())
            }
        }
        pages
    }
}
