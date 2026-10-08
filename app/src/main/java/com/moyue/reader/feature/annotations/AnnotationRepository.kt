package com.moyue.reader.feature.annotations

import com.moyue.reader.core.database.*
import com.moyue.reader.core.model.ReaderChapter
import com.moyue.reader.core.model.ReaderPosition
import org.json.JSONArray
import org.json.JSONObject

data class AnnotationDraft(val bookId: Long, val anchor: String, val text: String, val location: String, val hash: String = "", val type: String = "HIGHLIGHT")

fun textDraft(bookId: Long, chapter: ReaderChapter, start: ReaderPosition, end: ReaderPosition, type: String = "HIGHLIGHT"): AnnotationDraft {
    val text = sourceSelection(chapter.blocks, start, end)
    val source = blockText(chapter.blocks[start.blockIndex])
    return AnnotationDraft(bookId, JSONObject().put("kind", "TEXT").put("chapterId", chapter.id).put("chapterIndex", chapter.index)
        .put("blockIndex", start.blockIndex).put("charOffset", start.charOffset).put("endBlock", end.blockIndex).put("endOffset", end.charOffset)
        .put("quote", text).put("before", source.take(start.charOffset).takeLast(40)).put("after", source.drop(if (start.blockIndex == end.blockIndex) end.charOffset else source.length).take(40)).toString(),
        text, chapter.title, textHash(source), type)
}

class AnnotationRepository(private val database: MoyueDatabase) {
    suspend fun save(draft: AnnotationDraft, note: String, color: String): Long {
        require(draft.type == "BOOKMARK" || draft.text.isNotBlank()) { "请先选择文字" }
        require(draft.anchor.length < 65536 && draft.text.length <= 32768 && note.length <= 32768) { "标注内容过长" }
        val now = System.currentTimeMillis()
        return database.annotationDao().insert(AnnotationEntity(bookId = draft.bookId, type = if (note.isNotBlank() && draft.type != "BOOKMARK") "NOTE" else draft.type,
            anchorJson = draft.anchor, selectedText = draft.text, note = note, color = color, location = draft.location,
            sourceHash = draft.hash, createdAt = now, updatedAt = now))
    }

    fun json(items: List<AnnotationEntity>): String = JSONArray().also { array ->
        items.forEach { a -> array.put(JSONObject().put("id", a.id).put("anchor", JSONObject(a.anchorJson)).put("text", a.selectedText).put("hash", a.sourceHash).put("color", a.color)) }
    }.toString()

    fun export(items: List<AnnotationItem>): String = buildString {
        append("# 墨阅摘录\n\n")
        for (item in items) {
            val a = item.annotation
            append("## ").append(item.bookTitle.replace('\n', ' ')).append("\n\n")
            append("位置：").append(a.location.replace('\n', ' ')).append("\n\n")
            if (a.selectedText.isNotEmpty()) append(a.selectedText.lines().joinToString("\n") { "> $it" }).append("\n\n")
            if (a.note.isNotEmpty()) append("笔记：\n\n").append(a.note).append("\n\n")
            append("---\n\n")
        }
    }
}
