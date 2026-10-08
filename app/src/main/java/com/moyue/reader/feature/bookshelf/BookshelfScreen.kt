package com.moyue.reader.feature.bookshelf

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import com.moyue.reader.core.ui.MoyueMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.ui.BookCover
import com.moyue.reader.core.ui.MoyueRadius
import com.moyue.reader.core.ui.MoyueSpacing
import com.moyue.reader.core.ui.MoyueWordmark
import java.text.DateFormat
import java.util.Date

enum class ShelfFilter { ALL, RECENT, FINISHED, MARKDOWN, DOCUMENT, VISUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onAddBook: () -> Unit,
    onSettings: () -> Unit,
    onDeleteBook: (Long) -> Unit,
    onEditMarkdown: (Long) -> Unit = onOpenBook,
    onGlobalSearch: (() -> Unit)? = null,
    onAnnotations: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var actionSheet by remember { mutableStateOf<BookEntity?>(null) }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除《${book.title}》？") },
            text = { Text("将删除应用内的书籍副本、缓存和阅读进度。原始导入文件不受影响。") },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDeleteBook(book.id) }) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // Long-press actions. Destructive management no longer occupies a slot under every cover.
    actionSheet?.let { book ->
        ModalBottomSheet(
            onDismissRequest = { actionSheet = null },
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Tight),
                )
                HorizontalDivider(Modifier.padding(vertical = MoyueSpacing.Tight), color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("书籍详情") },
                    supportingContent = { Text("格式信息与阅读记录") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { actionSheet = null; onBookDetails(book.id) },
                )
                if (book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) {
                    ListItem(headlineContent = { Text("编辑 Markdown") }, supportingContent = { Text("编辑应用内副本，保存与导出") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { actionSheet = null; onEditMarkdown(book.id) })
                }
                ListItem(
                    headlineContent = { Text("删除书籍", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("同时清除阅读进度与缓存") },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { actionSheet = null; pendingDelete = book },
                )
            }
        }
    }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ShelfFilter.ALL) }
    var searchVisible by remember { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun closeSearch() { searchVisible=false; query=""; focus.clearFocus(); keyboard?.hide() }
    BackHandler(enabled=searchVisible && actionSheet==null && pendingDelete==null) { closeSearch() }
    val visible = books.filter { book ->
        val matchesQuery = query.isBlank() || book.title.contains(query, true) || book.author.orEmpty().contains(query, true)
        val matchesFilter = when (filter) {
            ShelfFilter.ALL -> true
            ShelfFilter.RECENT -> book.lastReadAt != null
            ShelfFilter.FINISHED -> book.progress >= .999f
            ShelfFilter.MARKDOWN -> book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN
            ShelfFilter.DOCUMENT -> book.sourceType == com.moyue.reader.core.model.SourceType.DOCUMENT && !isVisual(book)
            ShelfFilter.VISUAL -> isVisual(book)
        }
        matchesQuery && matchesFilter
    }
    val recent = books.maxByOrNull { it.lastReadAt ?: Long.MIN_VALUE }?.takeIf { it.lastReadAt != null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("墨阅", style = MoyueWordmark) },
                actions = {
                    IconButton(onClick = { if(onGlobalSearch != null) onGlobalSearch() else if(searchVisible) closeSearch() else searchVisible=true }) {
                        Icon(
                            if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchVisible) "关闭搜索" else "搜索书籍",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBook,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加内容") },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("书架") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onAnnotations,
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("摘录") },
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
            AnimatedVisibility(searchVisible, enter = MoyueMotion.expand(), exit = MoyueMotion.collapse()) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索书名或作者") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Tight),
                )
            }
            if (books.isEmpty()) {
                EmptyShelf(Modifier.weight(1f), onAddBook)
            } else {
                recent?.let { ContinueReadingCard(it, onOpenBook) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = MoyueSpacing.Page, end = MoyueSpacing.Page, top = MoyueSpacing.Section, bottom = MoyueSpacing.Micro),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的书架", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("共 ${books.size} 本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Tight),
                    horizontalArrangement = Arrangement.spacedBy(MoyueSpacing.Tight),
                ) {
                    ShelfFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "没有匹配的书籍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = MoyueSpacing.Page,
                            end = MoyueSpacing.Page,
                            top = MoyueSpacing.Micro,
                            bottom = 96.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(MoyueSpacing.Item),
                        verticalArrangement = Arrangement.spacedBy(MoyueSpacing.Section),
                    ) {
                        items(visible, key = BookEntity::id) { book ->
                            BookTile(
                                book = book,
                                modifier = Modifier.animateItem(fadeInSpec = tween(MoyueMotion.Standard), placementSpec = tween(MoyueMotion.Standard, easing = MoyueMotion.Easing), fadeOutSpec = tween(MoyueMotion.Fast)),
                                onOpen = { onOpenBook(book.id) },
                                onLongPress = { actionSheet = book },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier, onAddBook: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            BookCover(
                title = "墨阅",
                author = null,
                coverPath = null,
                modifier = Modifier.height(196.dp).width(140.dp),
            )
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp))
            Text(
                "导入书籍、PDF、图片或漫画，离线继续阅读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MoyueSpacing.Tight),
            )
            OutlinedButton(onClick = onAddBook, modifier = Modifier.padding(top = 24.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("添加第一本书", Modifier.padding(start = MoyueSpacing.Tight))
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onOpen: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = MoyueSpacing.Page)) {
        Text(
            "继续阅读",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MoyueSpacing.Section, bottom = MoyueSpacing.Tight),
        )
        Card(
            onClick = { onOpen(book.id) },
            shape = MoyueRadius.heroShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(MoyueSpacing.Item), verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    title = book.title,
                    author = book.author,
                    coverPath = book.coverPath,
                    sourceType = book.sourceType,
                    modifier = Modifier.height(116.dp).width(83.dp),
                )
                Column(Modifier.weight(1f).padding(horizontal = MoyueSpacing.Section)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        book.author ?: when(book.sourceType) { com.moyue.reader.core.model.SourceType.MARKDOWN -> "Markdown 文档"; com.moyue.reader.core.model.SourceType.DOCUMENT -> documentLabel(book); else -> "本地书籍" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isImage(book)) LinearProgressIndicator(
                        progress = { book.progress },
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = MoyueSpacing.Section).height(3.dp),
                    )
                    Text(
                        (if(isImage(book)) "最近查看" else "${(book.progress * 100).toInt()}%") + " · ${relativeTime(book.lastReadAt ?: book.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MoyueSpacing.Tight),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookEntity, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        BookCover(
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
                    sourceType = book.sourceType,
            modifier = Modifier.fillMaxWidth().aspectRatio(.7f),
        )
        Text(
            book.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MoyueSpacing.Tight),
        )
        Text(
            (when(book.sourceType) { com.moyue.reader.core.model.SourceType.MARKDOWN -> "MD · "; com.moyue.reader.core.model.SourceType.DOCUMENT -> documentLabel(book) + " · "; else -> "" }) + (if(isImage(book)) "查看图片" else progressLabel(book.progress)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isImage(book:BookEntity) = com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format == com.moyue.reader.core.document.DocumentFormat.IMAGE
private fun isVisual(book:BookEntity) = com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format in setOf(com.moyue.reader.core.document.DocumentFormat.IMAGE,com.moyue.reader.core.document.DocumentFormat.COMIC)
private fun documentLabel(book:BookEntity) = when(com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format) {
    com.moyue.reader.core.document.DocumentFormat.IMAGE -> "图片"
    com.moyue.reader.core.document.DocumentFormat.COMIC -> "漫画"
    else -> "PDF"
}

private fun progressLabel(progress: Float): String = when {
    progress >= .999f -> "已读完"
    progress > 0f -> "${(progress * 100).toInt()}%"
    else -> "未开始"
}

private fun relativeTime(millis: Long): String {
    val elapsed = System.currentTimeMillis() - millis
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> DateFormat.getDateInstance().format(Date(millis))
    }
}

private val ShelfFilter.label get() = when (this) {
    ShelfFilter.ALL -> "全部"
    ShelfFilter.RECENT -> "最近阅读"
    ShelfFilter.FINISHED -> "已读完"
    ShelfFilter.MARKDOWN -> "Markdown"
    ShelfFilter.DOCUMENT -> "文档"
    ShelfFilter.VISUAL -> "图片/漫画"
}
