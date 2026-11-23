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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.moyue.reader.core.ui.PaperIcons
import com.moyue.reader.core.ui.PaperChip
import com.moyue.reader.core.ui.PaperNavigation
import com.moyue.reader.core.ui.PaperLandscape
import com.moyue.reader.core.ui.PaperShelfTheme
import com.moyue.reader.core.document.DocumentFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import com.moyue.reader.core.ui.MoyueSpacing

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
    onLibrary: () -> Unit = {},
    onCollections: () -> Unit = {},
) = PaperShelfTheme {
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
    actionSheet?.let { id -> BookManagementSheet(id, library, repository,
        { actionSheet = null }, { actionSheet = null; pendingDelete = setOf(id) },
        { actionSheet = null; onBookDetails(id) },
        if (books.firstOrNull { it.id == id }?.sourceType == com.moyue.reader.core.model.SourceType.MARKDOWN) ({ actionSheet = null; onEditMarkdown(id) }) else null) }
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
    categoryDelete?.let { value -> AlertDialog(onDismissRequest = { if (!busy) categoryDelete = null }, title = { Text("删除分类“${value.name}”？") },
        text = { Text("仅移除分类及其关联。书籍、阅读进度和标注全部保留。") }, dismissButton = { TextButton(enabled = !busy, onClick = { categoryDelete = null }) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform({ repository.deleteCategory(value.tag, value.id) }, { categoryDelete = null }) }) { Text("删除分类") } }) }

    val bookFormats = setOf(DocumentFormat.TXT, DocumentFormat.EPUB, DocumentFormat.WEB)
    val docFormats = setOf(DocumentFormat.PDF, DocumentFormat.MARKDOWN)
    val quickFormats = listOf("全部" to emptySet(), "书籍" to bookFormats,
        "文档" to docFormats, "漫画" to setOf(DocumentFormat.COMIC))
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selecting) "已选 ${selected.size} 本" else "墨阅",
                        style = if (selecting) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f).clickable(enabled = !selecting, onClick = onLibrary))
                    if (selecting) {
                        TextButton(enabled = !busy, onClick = { selected = if (visible.isNotEmpty() && visible.all { it.book.id in selected }) selected - visible.map { it.book.id }.toSet() else selected + visible.map { it.book.id } }) { Text("全选") }
                        TextButton(enabled = !busy, onClick = { exitSelection() }) { Text("取消") }
                    } else {
                        IconButton(onClick = { if (onGlobalSearch != null) onGlobalSearch() else if (searchVisible) closeSearch() else searchVisible = true }) {
                            Icon(PaperIcons.Search, contentDescription = if (searchVisible) "关闭搜索" else "搜索书籍", modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = onAddBook) { Icon(PaperIcons.Add, "添加内容", Modifier.size(22.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickFormats.forEach { (label, formats) ->
                            PaperChip(effective.formats == formats, { onPreferences { it.copy(formats = formats) } }, { Text(label) }, enabled = !selecting && !busy)
                        }
                    }
                    IconButton(enabled = !selecting, onClick = { filterDialog = true }) { Icon(PaperIcons.Filter, "筛选书籍", Modifier.size(22.dp)) }
                }
            }
        },
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
            } else PaperNavigation(0, {}, onAnnotations, onSettings)
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val columns = when { maxWidth >= 840.dp -> 6; maxWidth >= 600.dp -> 5; maxWidth >= 480.dp -> 4; else -> 3 }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(if (columns == 3) 27.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (searchVisible) item(span = { GridItemSpan(maxLineSpan) }) {
                    TextField(query, { query = it }, placeholder = { Text("搜索书名或作者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (!selecting && recent != null) item(span = { GridItemSpan(maxLineSpan) }) { ContinueReadingCard(recent, onOpenBook) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                Row(Modifier.heightIn(min = 48.dp).clickable(enabled = !selecting) { collectionMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(effective.shelfId?.let { id -> library.shelves.firstOrNull { it.id == id }?.name } ?: "我的书籍",
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                androidx.compose.material3.DropdownMenu(collectionMenu, { collectionMenu = false }) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("全部书籍 · ${books.size}") }, onClick = { onPreferences { it.copy(shelfId = null) }; collectionMenu = false })
                                    library.shelves.forEach { shelf ->
                                        androidx.compose.material3.DropdownMenuItem(text = { Text(shelf.name) }, onClick = { onPreferences { it.copy(shelfId = shelf.id) }; collectionMenu = false })
                                    }
                                    HorizontalDivider()
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("我的书库") }, onClick = { collectionMenu = false; onLibrary() })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("书架与分类") }, onClick = { collectionMenu = false; onCollections() })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("阅读状态与收藏") }, onClick = { collectionMenu = false; statusMenu = true })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("管理书架") }, onClick = { collectionMenu = false; catalog = false })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("管理标签") }, onClick = { collectionMenu = false; catalog = true })
                                }
                                androidx.compose.material3.DropdownMenu(statusMenu, { statusMenu = false }) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("全部状态") }, onClick = { onPreferences { it.copy(status = null) }; statusMenu = false })
                                    ReadingStatus.entries.forEach { value ->
                                        androidx.compose.material3.DropdownMenuItem(text = { Text(value.label + if (effective.status == value) " ✓" else "") }, onClick = { onPreferences { it.copy(status = if (it.status == value) null else value) }; statusMenu = false })
                                    }
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("仅收藏" + if (effective.favoritesOnly) " ✓" else "") }, onClick = { onPreferences { it.copy(favoritesOnly = !it.favoritesOnly) }; statusMenu = false })
                                }
                            }
                            TextButton(enabled = !selecting, onClick = { selecting = true }) { Text("多选", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                            IconButton(enabled = !selecting, onClick = { sortDialog = true }) { Icon(PaperIcons.Filter, "排序方式 · ${effective.sort.label}", Modifier.size(18.dp)) }
                        }
                        val filterCount = effective.authors.size + effective.tagIds.size + (if (effective.status != null) 1 else 0) + (if (effective.favoritesOnly) 1 else 0)
                        if (filterCount > 0 || effective.formats !in quickFormats.map { it.second }) {
                            TextButton(onClick = { filterDialog = true }, enabled = !selecting) { Text("${visible.size} / ${books.size} 本 · ${effective.status?.label.orEmpty()} ${if (effective.favoritesOnly) "仅收藏" else ""} · 筛选中") }
                        }
                    }
                }
                if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyShelf(Modifier.fillMaxWidth().heightIn(min = 420.dp), onAddBook) }
                else if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("没有匹配的书籍")
                        TextButton(enabled = !selecting, onClick = { onPreferences { ShelfPreferences(sort = it.sort) }; query = "" }) { Text("清除筛选") }
                    }
                }
                items(visible, key = { it.book.id }) { item ->
                    BookTile(book = item.book, status = item.status, favorite = item.management.firstOrNull()?.favorite == true,
                        selected = item.book.id in selected, selecting = selecting,
                        modifier = Modifier.animateItem(fadeInSpec = tween(MoyueMotion.Standard), placementSpec = tween(MoyueMotion.Standard), fadeOutSpec = tween(MoyueMotion.Fast)),
                        onOpen = { if (selecting) { if (!busy) toggle(item.book.id) } else onBookDetails(item.book.id) },
                        onLongPress = { if (selecting) { if (!busy) toggle(item.book.id) } else actionSheet = item.book.id })
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
            Box(Modifier.width(240.dp).height(180.dp).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                PaperLandscape(Modifier.matchParentSize())
                androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Icon(PaperIcons.Book, null, Modifier.padding(28.dp).size(44.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp))
            Text(
                "导入书籍、PDF、图片或漫画，离线继续阅读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MoyueSpacing.Tight),
            )
            androidx.compose.material3.Button(onClick = onAddBook, modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("添加第一本书", Modifier.padding(start = MoyueSpacing.Tight))
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onOpen: (Long) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        PaperLandscape(Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color(0xFFE1D5C1).copy(alpha = .23f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(title = book.title, author = book.author, coverPath = book.coverPath, sourceType = book.sourceType,
                modifier = Modifier.width(96.dp).height(136.dp).shadow(3.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(start = 22.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text((book.author ?: "本地书籍") + if (isImage(book)) " · 最近查看" else " · 已读 ${(book.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (!isImage(book)) LinearProgressIndicator(progress = { book.progress.coerceIn(0f, 1f) },
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                    gapSize = 0.dp, drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp).height(3.dp))
                androidx.compose.material3.Button(onClick = { onOpen(book.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) { Text(if (isImage(book)) "继续查看" else "继续阅读") }
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
                modifier = Modifier.fillMaxWidth().aspectRatio(96f / 136f).shadow(3.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)))
            if (selecting) androidx.compose.material3.Checkbox(selected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)))
            else if (favorite) Text("★", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).padding(horizontal = 6.dp))
        }
        Text(
            book.title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
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
