package com.moyue.reader.feature.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(book: BookEntity, chapters: List<ChapterEntity>, onBack: () -> Unit,
    onRead: () -> Unit, onDelete: () -> Unit,
    documentMetadata: com.moyue.reader.core.database.DocumentMetadataEntity? = null, imageSize: String? = null,
    favorite: Boolean = false, onFavorite: () -> Unit = {}, onManage: () -> Unit = {}, onChapter: (Int) -> Unit = {}) {
    val format = com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format
    val image = format == com.moyue.reader.core.document.DocumentFormat.IMAGE
    val comic = format == com.moyue.reader.core.document.DocumentFormat.COMIC
    var confirmDelete by remember { mutableStateOf(false) }
    val metadata = if (book.sourceType == com.moyue.reader.core.model.SourceType.DOCUMENT) {
        (if (image) "图片" else if (comic) "漫画" else "PDF 文档") +
            (if (image) imageSize?.let { " · $it" }.orEmpty() else documentMetadata?.pageCount?.let { " · $it 页" }.orEmpty()) +
            (documentMetadata?.let { " · %.1f MB".format(it.byteSize / 1048576.0) } ?: "")
    } else "${sourceLabel(book.sourceType.name)} · ${formatSize(book.wordCount)} 字" +
        if (book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) "" else " · ${book.chapterCount} 章"
    Scaffold(topBar = { PaperTopBar("书籍详情", onBack) {
        IconButton(onManage) { Icon(PaperIcons.More, "管理书籍") }
        IconButton({ confirmDelete = true }) { Icon(PaperIcons.Trash, "删除书籍") }
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(264.dp), contentAlignment = Alignment.Center) {
                    PaperLandscape(Modifier.matchParentSize())
                    BookCover(book.title, book.author, book.coverPath,
                        Modifier.width(152.dp).height(216.dp).shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)), book.sourceType)
                }
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(book.author ?: if (image) "本地图片" else if (comic) "本地漫画" else "本地内容",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(metadata, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (image) "保存最近查看位置" else "已读 ${(book.progress * 100).toInt()}%", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                    Button(onRead, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)) {
                        Text(if (image) "查看图片" else if (book.progress > 0f) "继续阅读" else "开始阅读")
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PaperTool(if (favorite) "已收藏" else "收藏", PaperIcons.Heart, onFavorite)
                        PaperTool("书架与标签", PaperIcons.Tag, onManage)
                        PaperTool("更多", PaperIcons.More, onManage)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (chapters.isEmpty()) "文档内容" else "目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (chapters.isEmpty()) "在阅读页查看" else "共 ${chapters.size} 章", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(chapters, key = ChapterEntity::id) { chapter ->
                Row(Modifier.fillMaxWidth().clickable { onChapter(chapter.chapterIndex) }.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter.title, Modifier.weight(1f)); Icon(PaperIcons.Chevron, null, Modifier.size(16.dp))
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            }
            if (chapters.isEmpty()) item { Text(if (image) "支持缩放、旋转和位置标记" else if (comic) "支持缩略图、单页与连续阅读" else "继续阅读可查看大纲与文档内容",
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, icon = { Icon(PaperIcons.Trash, null) },
        title = { Text("删除《${book.title}》？") }, text = { Text("将删除应用内副本、缓存、阅读进度和标注。外部原始导入文件不受影响，此操作无法撤销。") },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
        confirmButton = { Button({ confirmDelete = false; onDelete() }) { Text("确认删除") } })
}

private fun sourceLabel(name: String): String = when (name) {
    "TXT" -> "本地 TXT"
    "EPUB" -> "本地 EPUB"
    "WEB" -> "网页导入"
    "MARKDOWN" -> "Markdown 文档"
    "DOCUMENT" -> "PDF 文档"
    else -> name
}

private fun formatSize(count: Long): String = when {
    count >= 10_000 -> "%.1f万".format(count / 10_000f)
    else -> count.toString()
}
