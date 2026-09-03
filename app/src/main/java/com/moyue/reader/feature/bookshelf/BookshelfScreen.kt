package com.moyue.reader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.ui.GeneratedCover
import java.text.DateFormat
import java.util.Date

enum class ShelfFilter { ALL, RECENT, FINISHED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onAddBook: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ShelfFilter.ALL) }
    var searchVisible by remember { mutableStateOf(false) }
    val visible = books.filter { book ->
        val matchesQuery = query.isBlank() || book.title.contains(query, true) || book.author.orEmpty().contains(query, true)
        val matchesFilter = when (filter) {
            ShelfFilter.ALL -> true
            ShelfFilter.RECENT -> book.lastReadAt != null
            ShelfFilter.FINISHED -> book.progress >= .999f
        }
        matchesQuery && matchesFilter
    }
    val recent = books.maxByOrNull { it.lastReadAt ?: Long.MIN_VALUE }?.takeIf { it.lastReadAt != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("墨阅", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索书籍")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBook,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加小说") },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("书架") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchVisible) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索书名或作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (books.isEmpty()) {
                EmptyShelf(Modifier.weight(1f), onAddBook)
            } else {
                recent?.let { ContinueReadingCard(it, onOpenBook, onBookDetails) }
                Text("我的书架", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 18.dp))
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShelfFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(visible, key = BookEntity::id) { book ->
                        BookTile(book, onOpenBook, onBookDetails)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier, onAddBook: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GeneratedCover("墨阅", Modifier.height(154.dp).aspectRatio(.7f))
            Text("一本书都没有", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Text("导入 TXT、EPUB，或净化一个网页开始阅读", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text("添加小说", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAddBook).padding(16.dp))
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onOpen: (Long) -> Unit, onDetails: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("继续阅读", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        Card(
            onClick = { onOpen(book.id) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GeneratedCover(book.title, Modifier.height(112.dp).aspectRatio(.7f))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(book.author ?: "本地书籍", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { book.progress }, Modifier.fillMaxWidth().padding(top = 16.dp))
                    Text("${(book.progress * 100).toInt()}% · ${DateFormat.getDateInstance().format(Date(book.lastReadAt ?: book.createdAt))}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
                Text("详情", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onDetails(book.id) }.padding(12.dp))
            }
        }
    }
}

@Composable
private fun BookTile(book: BookEntity, onOpen: (Long) -> Unit, onDetails: (Long) -> Unit) {
    Column {
        GeneratedCover(
            book.title,
            Modifier.fillMaxWidth().aspectRatio(.7f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { onOpen(book.id) },
        )
        Text(book.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clickable { onDetails(book.id) })
        Text(if (book.progress > 0f) "● ${(book.progress * 100).toInt()}%" else "未开始", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ShelfFilter.label get() = when (this) {
    ShelfFilter.ALL -> "全部"
    ShelfFilter.RECENT -> "最近阅读"
    ShelfFilter.FINISHED -> "已读完"
}
