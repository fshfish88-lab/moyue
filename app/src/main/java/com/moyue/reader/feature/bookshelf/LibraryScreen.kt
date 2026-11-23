package com.moyue.reader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.ui.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: MoyueContainer, collections: Boolean, onBack: () -> Unit,
    onSelect: (ShelfPreferences) -> Unit, onCollections: () -> Unit, onAdd: () -> Unit,
    onAnnotations: () -> Unit, onSettings: () -> Unit) {
    val library by container.shelves.library.collectAsState(initial = ShelfLibrary())
    var catalog by remember { mutableStateOf<Boolean?>(null) }
    var edit by remember { mutableStateOf<Triple<Boolean, Long?, String>?>(null) }
    var deletion by remember { mutableStateOf<Triple<Boolean, Long, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit, done: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { action(); done() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "操作失败，请重试"
        } finally { busy = false } }
    }
    Scaffold(topBar = { PaperTopBar(if (collections) "书架与分类" else "我的书库", onBack) {
        IconButton({ edit = Triple(false, null, "") }) { Icon(PaperIcons.Add, "新建书架") }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            if (!collections) {
                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PaperIcons.Book, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.padding(start = 20.dp)) {
                        Text("阅读，回到自己", style = MaterialTheme.typography.titleLarge)
                        Text("所有内容与阅读记录保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperStat(library.books.size, "全部书籍", Modifier.weight(1f)) { onSelect(ShelfPreferences()) }
                    PaperStat(library.books.count { it.status == ReadingStatus.READING }, "正在阅读", Modifier.weight(1f)) { onSelect(ShelfPreferences(status = ReadingStatus.READING)) }
                    PaperStat(library.books.count { it.management.firstOrNull()?.favorite == true }, "已经收藏", Modifier.weight(1f)) { onSelect(ShelfPreferences(favoritesOnly = true)) }
                }
                Spacer(Modifier.height(24.dp))
                PaperCard {
                    PaperRow("我的书架", "自定义书架与标签", PaperIcons.Folder, onClick = onCollections)
                    PaperRow("摘录与书签", "高亮、笔记与原文定位", PaperIcons.Notes, onClick = onAnnotations)
                    PaperRow("添加内容", "本地文件与网页", PaperIcons.Add, onClick = onAdd)
                    PaperRow("应用设置", "阅读、导入与软件更新", PaperIcons.Settings, onClick = onSettings)
                }
                Text("把书留在身边。\n把阅读留给自己。", Modifier.padding(top = 40.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("按自己的方式，整理每一本书。", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                data class Tile(val title: String, val count: Int, val preferences: ShelfPreferences)
                val tiles = listOf(Tile("全部书籍", library.books.size, ShelfPreferences()),
                    Tile("最近阅读", library.books.count { it.book.lastReadAt != null }, ShelfPreferences(sort = ShelfSort.RECENT)),
                    Tile("我的收藏", library.books.count { it.management.firstOrNull()?.favorite == true }, ShelfPreferences(favoritesOnly = true))) +
                    library.shelves.map { shelf -> Tile(shelf.name, library.books.count { b -> b.shelves.any { it.id == shelf.id } }, ShelfPreferences(shelfId = shelf.id)) }
                tiles.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile ->
                            PaperCard(Modifier.weight(1f).clickable { onSelect(tile.preferences) }) {
                                Column(Modifier.padding(18.dp)) {
                                    Icon(PaperIcons.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Text(tile.title, Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
                                    Text("${tile.count} 本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                PaperSection("我的标签")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.tags.forEach { tag -> PaperChip(false, { onSelect(ShelfPreferences(tagIds = setOf(tag.id))) }, { Text(tag.name) }) }
                    if (library.tags.isEmpty()) Text("给书籍加个标签，分类会更清楚。", style = MaterialTheme.typography.bodySmall)
                }
                PaperSection("整理书库")
                PaperCard {
                    PaperRow("管理书架", "新建、重命名或删除分类", PaperIcons.Folder) { catalog = false }
                    PaperRow("管理标签", "一本书可加入多个标签", PaperIcons.Tag) { catalog = true }
                }
            }
        }
    }
    catalog?.let { tag -> ShelfCatalogDialog(tag, library, busy, { catalog = null }, { edit = Triple(tag, null, "") },
        { id, name -> edit = Triple(tag, id, name) }, { id, name -> deletion = Triple(tag, id, name) }) }
    edit?.let { (tag, id, name) -> ShelfNameDialog(if (id == null) "新建${if (tag) "标签" else "书架"}" else "重命名", name, busy, { edit = null }) { value ->
        perform({ container.shelves.saveCategory(tag, id, value) }, { edit = null })
    } }
    deletion?.let { (tag, id, name) -> AlertDialog(onDismissRequest = { if (!busy) deletion = null },
        title = { Text("删除分类“$name”？") }, text = { Text("仅移除分类关系，书籍、进度和标注全部保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform({ container.shelves.deleteCategory(tag, id) }, { deletion = null }) }) { Text("删除分类") } },
        dismissButton = { TextButton({ deletion = null }, enabled = !busy) { Text("取消") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("知道了") } }) }
}
