package com.moyue.reader.feature.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.ui.BookCover
import com.moyue.reader.core.ui.MoyueSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    book: BookEntity,
    chapters: List<ChapterEntity>,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit,
    documentMetadata: com.moyue.reader.core.database.DocumentMetadataEntity? = null,
    imageSize: String? = null,
) {
    val format = com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format
    val image = format == com.moyue.reader.core.document.DocumentFormat.IMAGE
    val comic = format == com.moyue.reader.core.document.DocumentFormat.COMIC
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("书籍详情") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "删除书籍") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.fillMaxWidth().padding(MoyueSpacing.Page)) {
                    BookCover(
                        title = book.title,
                        author = book.author,
                        coverPath = book.coverPath,
                        sourceType = book.sourceType,
                        modifier = Modifier.width(112.dp).aspectRatio(.7f),
                    )
                    Column(Modifier.weight(1f).padding(start = MoyueSpacing.Section)) {
                        Text(book.title, style = MaterialTheme.typography.headlineSmall)
                        Text(book.author ?: if(image) "本地图片" else if(comic) "本地漫画" else if (book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) "Markdown 文档" else "未知作者", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            if(book.sourceType == com.moyue.reader.core.model.SourceType.DOCUMENT) {
                                (if(image) "图片" else if(comic) "漫画" else "PDF 文档") + (if(image) imageSize?.let {" · $it"}.orEmpty() else documentMetadata?.pageCount?.let { " · $it 页" }.orEmpty()) + (documentMetadata?.let { " · %.1f MB".format(it.byteSize/1048576.0) } ?: "")
                            } else "${sourceLabel(book.sourceType.name)} · ${formatSize(book.wordCount)} 字" + (if (book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) "" else " · ${book.chapterCount} 章"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MoyueSpacing.Tight),
                        )
                        if(!image) LinearProgressIndicator(
                            progress = { book.progress },
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = MoyueSpacing.Item).height(3.dp),
                        )
                        Text(
                            if(image) "保存最近查看位置" else if (book.progress >= .999f) "已读完" else "已读 ${(book.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // Continue from the stored position: pass no explicit chapter so the reader
                        // restores the saved chapter and block position instead of landing on the cover.
                        Button(
                            onClick = onRead,
                            modifier = Modifier.padding(top = MoyueSpacing.Item),
                        ) {
                            Text(if(image) "查看图片" else if (book.progress > 0f) "继续阅读" else "开始阅读")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Section),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (book.sourceType in setOf(com.moyue.reader.core.model.SourceType.MARKDOWN,com.moyue.reader.core.model.SourceType.DOCUMENT)) "文档内容" else "目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(
                        when(book.sourceType) { com.moyue.reader.core.model.SourceType.MARKDOWN -> "阅读页查看大纲"; com.moyue.reader.core.model.SourceType.DOCUMENT -> if(image) "支持缩放与旋转" else if(comic) "阅读页查看缩略图" else "阅读页查看缩略图与大纲"; else -> "共 ${chapters.size} 章" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(chapters, key = ChapterEntity::id) { chapter ->
                Text(
                    chapter.title,
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MoyueSpacing.Page, vertical = 14.dp),
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除《${book.title}》？") },
            text = { Text("书籍副本、缓存和阅读进度都会从本机删除，此操作无法撤销。") },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
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
