package com.moyue.reader.feature.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.ui.GeneratedCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    book: BookEntity,
    chapters: List<ChapterEntity>,
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍详情") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "删除书籍") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.fillMaxWidth().padding(20.dp)) {
                    GeneratedCover(book.title, Modifier.fillMaxWidth(.28f).aspectRatio(.7f))
                    Column(Modifier.weight(1f).padding(start = 20.dp)) {
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(book.author ?: "未知作者", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Text("${book.sourceType.name} · ${formatSize(book.wordCount)} 字", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        LinearProgressIndicator(progress = { book.progress }, Modifier.fillMaxWidth().padding(top = 16.dp))
                        Text("已读 ${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                        Button(onClick = { onRead(chapters.indexOfFirst { it.id == chapters.firstOrNull()?.id }.coerceAtLeast(0)) }, modifier = Modifier.padding(top = 16.dp)) { Text("继续阅读") }
                    }
                }
                HorizontalDivider()
                Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            }
            items(chapters, key = ChapterEntity::id) { chapter ->
                Text(chapter.title, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除《${book.title}》？") },
            text = { Text("书籍副本、缓存和阅读进度都会从本机删除，此操作无法撤销。") },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
            confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("确认删除") } },
        )
    }
}

private fun formatSize(count: Long): String = when {
    count >= 10_000 -> "%.1f万".format(count / 10_000f)
    else -> count.toString()
}
