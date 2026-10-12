package com.moyue.reader.feature.bookshelf

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.navigationBarsPadding
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

private data class CategoryEditor(val tag: Boolean, val id: Long?, val name: String = "")
private data class CategoryDeletion(val tag: Boolean, val id: Long, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    library: ShelfLibrary,
    preferences: ShelfPreferences,
    repository: ShelfRepository,
    onPreferences: ((ShelfPreferences) -> ShelfPreferences) -> Unit,
    onMessage: (String) -> Unit,
    onDeleteBooks: suspend (Set<Long>) -> Set<Long>,
    onOpenBook: (Long) -> Unit,
    onBookDetails: (Long) -> Unit,
    onAddBook: () -> Unit,
    onSettings: () -> Unit,
    onEditMarkdown: (Long) -> Unit = onOpenBook,
    onGlobalSearch: (() -> Unit)? = null,
    onAnnotations: () -> Unit = {},
) {
    var actionSheet by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var filterDialog by remember { mutableStateOf(false) }
    var statusTargets by remember { mutableStateOf<Set<Long>?>(null) }
    var assignment by remember { mutableStateOf<Pair<Boolean, Set<Long>>?>(null) }
    var catalog by remember { mutableStateOf<Boolean?>(null) }
    var editor by remember { mutableStateOf<CategoryEditor?>(null) }
    var categoryDelete by remember { mutableStateOf<CategoryDeletion?>(null) }
    var seriesBook by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun perform(action: suspend () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); done() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onMessage(e.message?.takeIf { it.length < 120 } ?: "操作失败，请重试")
            } finally { busy = false }
        }
    }
    fun closeSearch() { searchVisible = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    fun exitSelection() { selecting = false; selected = emptySet() }
    androidx.compose.runtime.LaunchedEffect(books.map { it.id }) { selected = selected.intersect(books.map { it.id }.toSet()) }
    BackHandler(enabled = selecting && !busy && pendingDelete == null && assignment == null && statusTargets == null) { exitSelection() }
    BackHandler(enabled = searchVisible && !selecting && actionSheet == null) { closeSearch() }
    val effective = preferences.copy(
        shelfId = preferences.shelfId?.takeIf { id -> library.shelves.any { it.id == id } },
        tagIds = preferences.tagIds.intersect(library.tags.map { it.id }.toSet()),
    )
    val visible = shelfBooks(library.books, effective, query)
    val recent = books.maxByOrNull { it.lastReadAt ?: Long.MIN_VALUE }?.takeIf { it.lastReadAt != null }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }

    pendingDelete?.let { ids ->
        val targets = books.filter { it.id in ids }
        AlertDialog(onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(if (targets.size == 1) "删除《${targets.first().title}》？" else "删除 ${targets.size} 本书籍？") },
            text = { Column { Text(targets.take(4).joinToString("、") { it.title } + if (targets.size > 4) " 等" else "")
                Text("将删除应用内副本、缓存、阅读进度和标注，原始导入文件不受影响。此操作无法撤销。", Modifier.padding(top = 12.dp)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingDelete = null }) { Text("取消") } },
            confirmButton = { TextButton(enabled = !busy && targets.isNotEmpty(), onClick = {
                perform({ val removed = onDeleteBooks(ids); selected = selected - removed }, { pendingDelete = null; if (selected.isEmpty()) selecting = false })
            }) { Text(if (busy) "删除中…" else "确认删除", color = MaterialTheme.colorScheme.error) } })
    }
    actionSheet?.let { id -> library.books.firstOrNull { it.book.id == id }?.let { item ->
        val book = item.book
        ModalBottomSheet(onDismissRequest = { if (!busy) actionSheet = null }, containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
                ShelfAction(if (item.management.firstOrNull()?.favorite == true) "取消收藏" else "收藏书籍", !busy) {
                    perform({ repository.favorite(setOf(id), item.management.firstOrNull()?.favorite != true) }, { actionSheet = null })
                }
                ShelfAction("阅读状态 · ${item.status.label}") { actionSheet = null; statusTargets = setOf(id) }
                ShelfAction("加入书架", supporting = item.shelves.joinToString("、") { it.name }.ifBlank { "一本书可属于多个书架" }) { actionSheet = null; assignment = false to setOf(id) }
                ShelfAction("标签管理", supporting = item.tags.joinToString("、") { it.name }.ifBlank { "新增或移除多个标签" }) { actionSheet = null; assignment = true to setOf(id) }
                ShelfAction("系列信息", supporting = item.management.firstOrNull()?.series ?: "可随时补录") { actionSheet = null; seriesBook = id }
                ShelfAction("多选管理") { actionSheet = null; selecting = true; selected = setOf(id) }
                HorizontalDivider()
                ShelfAction("书籍详情") { actionSheet = null; onBookDetails(id) }
                if (book.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) ShelfAction("编辑 Markdown") { actionSheet = null; onEditMarkdown(id) }
                ShelfAction("删除书籍", destructive = true) { actionSheet = null; pendingDelete = setOf(id) }
            }
        }
    } }
    if (sortDialog) ShelfChoiceDialog("排序方式", ShelfSort.entries.map { it to it.label }, effective.sort, { sortDialog = false }) { value ->
        onPreferences { it.copy(sort = value) }; sortDialog = false
    }
    statusTargets?.let { ids -> ShelfChoiceDialog("设置阅读状态", ReadingStatus.entries.map { it to it.label },
        library.books.filter { it.book.id in ids }.map { it.status }.distinct().singleOrNull(), { statusTargets = null }) { value ->
        perform({ repository.status(ids, value) }, { statusTargets = null })
    } }
    if (filterDialog) ShelfFilterDialog(effective, library, { filterDialog = false }) { draft -> onPreferences { draft }; filterDialog = false }
    assignment?.let { (tag, ids) -> ShelfAssignmentDialog(tag, ids, library, busy, { assignment = null }, { editor = CategoryEditor(tag, null) }) { add, remove ->
        perform({ repository.categories(ids, tag, add, remove) }, { assignment = null })
    } }
    catalog?.let { tag -> ShelfCatalogDialog(tag, library, busy, { catalog = null }, { editor = CategoryEditor(tag, null) },
        { id, name -> editor = CategoryEditor(tag, id, name) }, { id, name -> categoryDelete = CategoryDeletion(tag, id, name) }) }
    editor?.let { value -> ShelfNameDialog(if (value.id == null) "新建${if (value.tag) "标签" else "书架"}" else "重命名", value.name, busy, { editor = null }) { name ->
        perform({ repository.saveCategory(value.tag, value.id, name) }, { editor = null })
    } }
    seriesBook?.let { id -> ShelfNameDialog("系列信息", library.books.firstOrNull { it.book.id == id }?.management?.firstOrNull()?.series.orEmpty(), busy,
        { seriesBook = null }, allowEmpty = true, onSave = { value -> perform({ repository.series(id, value) }, { seriesBook = null }) }) }
    categoryDelete?.let { value -> AlertDialog(onDismissRequest = { if (!busy) categoryDelete = null }, title = { Text("删除分类“${value.name}”？") },
        text = { Text("仅移除分类及其关联。书籍、阅读进度和标注全部保留。") }, dismissButton = { TextButton(enabled = !busy, onClick = { categoryDelete = null }) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform({ repository.deleteCategory(value.tag, value.id) }, { categoryDelete = null }) }) { Text("删除分类") } }) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            title = { Text(if (selecting) "已选 ${selected.size} 本" else "墨阅", style = if (selecting) MaterialTheme.typography.titleMedium else MoyueWordmark) },
            actions = {
                if (selecting) {
                    TextButton(enabled = !busy, onClick = { selected = if (visible.isNotEmpty() && visible.all { it.book.id in selected }) selected - visible.map { it.book.id }.toSet() else selected + visible.map { it.book.id } }) { Text("全选") }
                    TextButton(enabled = !busy, onClick = { exitSelection() }) { Text("完成") }
                } else {
                    TextButton(onClick = { selecting = true }) { Text("多选") }
                    IconButton(onClick = { if (onGlobalSearch != null) onGlobalSearch() else if (searchVisible) closeSearch() else searchVisible = true }) {
                        Icon(if (searchVisible) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searchVisible) "关闭搜索" else "搜索书籍")
                    }
                }
            }) },
        floatingActionButton = { if (!selecting) ExtendedFloatingActionButton(onClick = onAddBook, containerColor = MaterialTheme.colorScheme.primary,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("添加内容") }) },
        bottomBar = {
            if (selecting) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp)) {
                val enabled = selected.isNotEmpty() && !busy
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = enabled, onClick = { perform({ repository.favorite(selected, true) }) }) { Text("收藏") }
                    TextButton(enabled = enabled, onClick = { perform({ repository.favorite(selected, false) }) }) { Text("取消收藏") }
                    TextButton(enabled = enabled, onClick = { statusTargets = selected.toSet() }) { Text("状态") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = enabled, onClick = { assignment = false to selected.toSet() }) { Text("书架") }
                    TextButton(enabled = enabled, onClick = { assignment = true to selected.toSet() }) { Text("标签") }
                    TextButton(enabled = enabled, onClick = { pendingDelete = selected.toSet() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            } else NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                NavigationBarItem(true, {}, { Icon(Icons.Default.Home, null) }, label = { Text("书架") })
                NavigationBarItem(false, onAnnotations, { Icon(Icons.Default.Home, null) }, label = { Text("摘录") })
                NavigationBarItem(false, onSettings, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(searchVisible) { TextField(query, { query = it }, placeholder = { Text("搜索书名或作者") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
            if (!selecting) recent?.let { ContinueReadingCard(it, onOpenBook) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = effective.shelfId == null, enabled = !selecting && !busy,
                        onClick = { onPreferences { it.copy(shelfId = null) } }, label = { Text("全部书籍") })
                    library.shelves.forEach { shelf ->
                        FilterChip(selected = effective.shelfId == shelf.id, enabled = !selecting && !busy,
                            onClick = { onPreferences { it.copy(shelfId = if (it.shelfId == shelf.id) null else shelf.id) } },
                            label = { Text(shelf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    Box {
                        FilterChip(selected = false, enabled = !selecting && !busy, onClick = { collectionMenu = true }, label = { Text("管理 ▾") })
                        androidx.compose.material3.DropdownMenu(collectionMenu, { collectionMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("管理书架") }, onClick = { collectionMenu = false; catalog = false })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("管理标签") }, onClick = { collectionMenu = false; catalog = true })
                        }
                    }
                }
                TextButton(enabled = !selecting, onClick = { sortDialog = true }) { Text("${effective.sort.label} ▾", style = MaterialTheme.typography.labelMedium) }
                val filterCount = effective.formats.size + effective.authors.size + effective.tagIds.size
                TextButton(enabled = !selecting, onClick = { filterDialog = true }) { Text(if (filterCount == 0) "筛选" else "筛选($filterCount)") }
            }
            Box(Modifier.padding(horizontal = 16.dp)) {
                val statusLabel = effective.status?.label ?: "全部状态"
                TextButton(enabled = !selecting, onClick = { statusMenu = true }) {
                    Text("${if (effective.favoritesOnly) "$statusLabel · 收藏" else statusLabel} ▾")
                }
                androidx.compose.material3.DropdownMenu(statusMenu, { statusMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("全部状态") },
                        onClick = { onPreferences { it.copy(status = null) }; statusMenu = false },
                        trailingIcon = { if (effective.status == null) Text("✓") })
                    ReadingStatus.entries.forEach { value ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(value.label) },
                            onClick = { onPreferences { it.copy(status = if (it.status == value) null else value) }; statusMenu = false },
                            trailingIcon = { if (effective.status == value) Text("✓") })
                    }
                    HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(text = { Text("收藏") },
                        onClick = { onPreferences { it.copy(favoritesOnly = !it.favoritesOnly) }; statusMenu = false },
                        trailingIcon = { if (effective.favoritesOnly) Text("✓") })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("我的书架", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${visible.size} / ${books.size} 本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (books.isEmpty()) EmptyShelf(Modifier.weight(1f), onAddBook)
            else if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有匹配的书籍")
                    TextButton(enabled = !selecting, onClick = { onPreferences { ShelfPreferences(sort = it.sort) }; query = "" }) { Text("清除筛选") }
                }
            } else LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = if (selecting) 24.dp else 96.dp),
                horizontalArrangement = Arrangement.spacedBy(MoyueSpacing.Item), verticalArrangement = Arrangement.spacedBy(MoyueSpacing.Section)) {
                items(visible, key = { it.book.id }) { item ->
                    BookTile(book = item.book, status = item.status, favorite = item.management.firstOrNull()?.favorite == true,
                        selected = item.book.id in selected, selecting = selecting,
                        modifier = Modifier.animateItem(fadeInSpec = tween(MoyueMotion.Standard), placementSpec = tween(MoyueMotion.Standard), fadeOutSpec = tween(MoyueMotion.Fast)),
                        onOpen = { if (selecting) { if (!busy) toggle(item.book.id) } else onOpenBook(item.book.id) },
                        onLongPress = { if (selecting) { if (!busy) toggle(item.book.id) } else actionSheet = item.book.id })
                }
            }
        }
    }
}

@Composable
private fun ShelfAction(label: String, enabled: Boolean = true, supporting: String? = null, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        supportingContent = supporting?.let { { Text(it) } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick))
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
private fun BookTile(book: BookEntity, status: ReadingStatus, favorite: Boolean, selecting: Boolean, selected: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box {
            BookCover(title = book.title, author = book.author, coverPath = book.coverPath, sourceType = book.sourceType,
                modifier = Modifier.fillMaxWidth().aspectRatio(.7f))
            if (selecting) androidx.compose.material3.Checkbox(selected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)))
            else if (favorite) Text("★", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).padding(horizontal = 6.dp))
        }
        Text(
            book.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MoyueSpacing.Tight),
        )
        Text(
            (when(book.sourceType) { com.moyue.reader.core.model.SourceType.MARKDOWN -> "MD · "; com.moyue.reader.core.model.SourceType.DOCUMENT -> documentLabel(book) + " · "; else -> "" }) + status.label + (if (isImage(book)) "" else " · ${(book.progress * 100).toInt()}%"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isImage(book:BookEntity) = com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format == com.moyue.reader.core.document.DocumentFormat.IMAGE
private fun documentLabel(book:BookEntity) = when(com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format) {
    com.moyue.reader.core.document.DocumentFormat.IMAGE -> "图片"
    com.moyue.reader.core.document.DocumentFormat.COMIC -> "漫画"
    else -> "PDF"
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
