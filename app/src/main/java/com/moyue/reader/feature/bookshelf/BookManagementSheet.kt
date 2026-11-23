package com.moyue.reader.feature.bookshelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.ui.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookManagementSheet(bookId: Long, library: ShelfLibrary, repository: ShelfRepository,
    onDismiss: () -> Unit, onDelete: () -> Unit, onDetails: (() -> Unit)? = null, onEditMarkdown: (() -> Unit)? = null) {
    val item = library.books.firstOrNull { it.book.id == bookId } ?: return
    var busy by remember { mutableStateOf(false) }
    var assignment by remember { mutableStateOf<Boolean?>(null) }
    var create by remember { mutableStateOf<Boolean?>(null) }
    var series by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch { try { action(); done() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "保存失败，请重试"
        } finally { busy = false } }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("管理书籍", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(item.book.title, item.book.author, item.book.coverPath, Modifier.width(72.dp).height(102.dp), item.book.sourceType)
                Column(Modifier.weight(1f).padding(start = 18.dp)) {
                    Text(item.book.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.book.author ?: "本地内容", style = MaterialTheme.typography.bodySmall)
                    Text("${item.status.label} · 已读 ${(item.book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PaperCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PaperIcons.Heart, null, Modifier.size(22.dp)); Text("收藏", Modifier.weight(1f).padding(start = 14.dp))
                    Switch(item.management.firstOrNull()?.favorite == true, { value -> perform({ repository.favorite(setOf(bookId), value) }) }, enabled = !busy, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.secondary))
                }
            }
            PaperSection("阅读状态")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingStatus.entries.forEach { status -> PaperChip(item.status == status, { perform({ repository.status(setOf(bookId), status) }) }, { Text(status.label) }, enabled = !busy) }
            }
            Spacer(Modifier.height(16.dp))
            PaperCard {
                PaperRow("加入书架", item.shelves.joinToString("、") { it.name }.ifBlank { "尚未分类" }, PaperIcons.Folder, !busy) { assignment = false }
                PaperRow("标签管理", item.tags.joinToString("、") { it.name }.ifBlank { "编辑标签" }, PaperIcons.Tag, !busy) { assignment = true }
                PaperRow("系列信息", item.management.firstOrNull()?.series ?: "补录系列与分卷", PaperIcons.Book, !busy) { series = true }
                onDetails?.let { PaperRow("书籍详情", icon = PaperIcons.File, enabled = !busy, onClick = it) }
                onEditMarkdown?.let { PaperRow("编辑 Markdown", icon = PaperIcons.Edit, enabled = !busy, onClick = it) }
            }
            Button(onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)) { Text("完成") }
            TextButton(onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除书籍", color = MaterialTheme.colorScheme.error) }
        }
    }
    assignment?.let { tag -> ShelfAssignmentDialog(tag, setOf(bookId), library, busy, { assignment = null }, { create = tag }) { add, remove ->
        perform({ repository.categories(setOf(bookId), tag, add, remove) }, { assignment = null })
    } }
    create?.let { tag -> ShelfNameDialog("新建${if (tag) "标签" else "书架"}", "", busy, { create = null }) { value ->
        perform({ repository.saveCategory(tag, null, value) }, { create = null })
    } }
    if (series) ShelfNameDialog("系列信息", item.management.firstOrNull()?.series.orEmpty(), busy, { series = false }, allowEmpty = true) { value ->
        perform({ repository.series(bookId, value) }, { series = false })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("知道了") } }) }
}
