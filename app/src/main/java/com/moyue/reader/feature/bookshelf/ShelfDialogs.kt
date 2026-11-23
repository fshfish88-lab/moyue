package com.moyue.reader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.document.DocumentFormat
import com.moyue.reader.core.ui.*

@Composable
fun ShelfNameDialog(title: String, initial: String, busy: Boolean, onDismiss: () -> Unit, allowEmpty: Boolean = false, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true,
            label = { Text(if (allowEmpty) "系列名称（留空可移除）" else "名称") }, enabled = !busy)
    }, confirmButton = { TextButton(enabled = !busy && (allowEmpty || name.isNotBlank()), onClick = { onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
fun <T> ShelfChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(value) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == value, onClick = { onSelect(value) }); Text(label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Unchanged / all / none is retained separately, so a mixed bulk membership is never silently removed. */
@Composable
fun ShelfAssignmentDialog(tag: Boolean, ids: Set<Long>, library: ShelfLibrary, busy: Boolean, onDismiss: () -> Unit,
    onCreate: () -> Unit, onApply: (Set<Long>, Set<Long>) -> Unit) {
    var changes by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    val selected = library.books.filter { it.book.id in ids }
    val choices = if (tag) library.tags.map { it.id to it.name } else library.shelves.map { it.id to it.name }
    val existing = selected.map { item -> if (tag) item.tags.map { it.id }.toSet() else item.shelves.map { it.id }.toSet() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (tag) "标签管理" else "加入书架") }, text = {
        Column {
            Text(if (ids.size > 1) "已选 ${ids.size} 本；部分选中表示仅部分书籍拥有该分类。未调整的分类保持不变。" else "可同时加入多个分类。", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { (id, name) ->
                    val count = existing.count { id in it }
                    val state = changes[id]?.let { if (it) ToggleableState.On else ToggleableState.Off } ?: when {
                        count == 0 -> ToggleableState.Off
                        count == selected.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    val toggle = { changes = changes + (id to (state != ToggleableState.On)) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(state, onClick = toggle, enabled = !busy)
                        Column { Text(name); if (state == ToggleableState.Indeterminate) Text("部分书籍已加入", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (choices.isEmpty()) Text("尚未创建分类", Modifier.padding(vertical = 16.dp))
            }
            TextButton(enabled = !busy, onClick = onCreate) { Text(if (tag) "＋ 新建标签" else "＋ 新建书架") }
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val valid = choices.map { it.first }.toSet()
            onApply(changes.filter { it.value }.keys.intersect(valid), changes.filter { !it.value }.keys.intersect(valid))
        }) { Text("应用") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ShelfCatalogDialog(tag: Boolean, library: ShelfLibrary, busy: Boolean, onDismiss: () -> Unit,
    onCreate: () -> Unit, onRename: (Long, String) -> Unit, onDelete: (Long, String) -> Unit) {
    val choices = if (tag) library.tags.map { it.id to it.name } else library.shelves.map { it.id to it.name }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (tag) "管理标签" else "管理书架") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("删除分类只移除分类关系，书籍与阅读记录会保留。", style = MaterialTheme.typography.bodySmall)
            choices.forEach { (id, name) ->
                Column(Modifier.padding(top = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Row { TextButton(enabled = !busy, onClick = { onRename(id, name) }) { Text("重命名") }
                        TextButton(enabled = !busy, onClick = { onDelete(id, name) }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                    HorizontalDivider()
                }
            }
            if (choices.isEmpty()) Text("尚未创建分类", Modifier.padding(vertical = 16.dp))
            TextButton(enabled = !busy, onClick = onCreate) { Text(if (tag) "＋ 新建标签" else "＋ 新建书架") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfFilterDialog(preferences: ShelfPreferences, library: ShelfLibrary, onDismiss: () -> Unit, onApply: (ShelfPreferences) -> Unit) {
    var draft by remember { mutableStateOf(preferences) }
    var advanced by remember { mutableStateOf(false) }
    var authorQuery by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (advanced) "更多筛选" else "筛选书籍", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ advanced = !advanced }) { Text(if (advanced) "返回" else "更多") }
            }
            if (!advanced) {
                PaperSection("阅读状态")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperChip(draft.status == null, { draft = draft.copy(status = null) }, { Text("全部") })
                    ReadingStatus.entries.forEach { status -> PaperChip(draft.status == status, { draft = draft.copy(status = status) }, { Text(status.label) }) }
                }
                PaperSection("内容格式")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DocumentFormat.EPUB, DocumentFormat.TXT, DocumentFormat.PDF, DocumentFormat.MARKDOWN).forEach { value ->
                        PaperChip(value in draft.formats, { draft = draft.copy(formats = if (value in draft.formats) draft.formats - value else draft.formats + value) }, { Text(formatLabel(value)) })
                    }
                }
                PaperSection("标签（需全部匹配）")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.tags.forEach { tag -> PaperChip(tag.id in draft.tagIds, { draft = draft.copy(tagIds = if (tag.id in draft.tagIds) draft.tagIds - tag.id else draft.tagIds + tag.id) }, { Text(tag.name) }) }
                }
                if (library.tags.isEmpty()) Text("长按书籍可新建和分配标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PaperSection("排序")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfSort.entries.forEach { sort -> PaperChip(draft.sort == sort, { draft = draft.copy(sort = sort) }, { Text(sort.label) }) }
                }
            } else {
                PaperSection("收藏")
                FilterRow("仅收藏", draft.favoritesOnly) { draft = draft.copy(favoritesOnly = it) }
                PaperSection("全部格式")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocumentFormat.entries.forEach { value -> PaperChip(value in draft.formats,
                        { draft = draft.copy(formats = if (value in draft.formats) draft.formats - value else draft.formats + value) }, { Text(formatLabel(value)) }) }
                }
                PaperSection("作者")
                OutlinedTextField(authorQuery, { authorQuery = it }, placeholder = { Text("搜索作者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    (library.books.map { it.book.author.orEmpty() }.toSet() + draft.authors).sorted().filter { it.contains(authorQuery, true) }.forEach { author ->
                        FilterRow(author.ifBlank { "未知作者" }, author in draft.authors) { draft = draft.copy(authors = if (it) draft.authors + author else draft.authors - author) }
                    }
                }
                Text("同一维度任一匹配，不同维度同时匹配，所选标签需全部匹配。", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ draft = ShelfPreferences(sort = draft.sort) }) { Text("清除所有筛选") }
            Button({ onApply(draft) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("显示 ${shelfBooks(library.books, draft).size} 本书 · 应用筛选") }
        }
    }
}

@Composable
private fun FilterRow(label: String, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onSelect(!selected) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onSelect); Text(label)
    }
}
