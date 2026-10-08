package com.moyue.reader.feature.annotations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.database.*
import kotlinx.coroutines.*
import org.json.JSONObject

val annotationColors = linkedMapOf("yellow" to "黄色", "green" to "绿色", "blue" to "蓝色", "pink" to "粉色")
fun annotationColor(value: String): Int = when (value) { "green" -> 0x666FCF97; "blue" -> 0x666CAFF0; "pink" -> 0x66F493B2; else -> 0x66EFC752 }
private fun typeLabel(type: String) = when (type) { "BOOKMARK" -> "书签"; "NOTE" -> "笔记"; else -> "高亮" }

@Composable
fun AnnotationEditor(draft: AnnotationDraft, existing: AnnotationEntity? = null, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var note by remember(draft, existing) { mutableStateOf(existing?.note.orEmpty()) }
    var color by remember(draft, existing) { mutableStateOf(existing?.color ?: "yellow") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "保存${typeLabel(draft.type)}" else "编辑标注") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (draft.text.isNotBlank()) Text(draft.text.take(300), maxLines = 7)
            Text(draft.location, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                annotationColors.forEach { (key, label) -> FilterChip(selected = color == key, onClick = { color = key }, label = { Text(label) }) }
            }
            OutlinedTextField(note, { if (it.length <= 32768) note = it }, label = { Text("笔记（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
        } }, confirmButton = { TextButton(onClick = { onSave(note, color) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun AnnotationRow(item: AnnotationEntity, title: String? = null, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text("${typeLabel(item.type)} · ${item.location} · ${annotationColors[item.color].orEmpty()}", style = MaterialTheme.typography.labelMedium)
            if (item.selectedText.isNotBlank()) Text(item.selectedText, maxLines = 5)
            if (item.note.isNotBlank()) Text(item.note, maxLines = 4, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onOpen) { Text("返回原文") }; TextButton(onEdit) { Text("编辑") }; TextButton(onDelete) { Text("删除") }
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationTools(container: MoyueContainer, bookId: Long, draft: AnnotationDraft?, onDraft: (AnnotationDraft?) -> Unit,
                    showList: Boolean, onList: (Boolean) -> Unit, onOpen: (AnnotationEntity) -> Unit) {
    val items by container.database.annotationDao().forBook(bookId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<AnnotationEntity?>(null) }
    var delete by remember { mutableStateOf<AnnotationEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showList) ModalBottomSheet(onDismissRequest = { onList(false) }) {
        Text("本书标注 · ${items.size}", Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) Text("长按正文选择文字，或添加当前位置书签", Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
            items(items, key = { it.id }) { a -> AnnotationRow(a, onOpen = { onList(false); onOpen(a) }, onEdit = { edit = a }, onDelete = { delete = a }) }
        }
    }
    val editing = edit ?: draft?.let { current -> items.firstOrNull { sameAnnotationRange(it, current) } }
    val currentDraft = editing?.let { AnnotationDraft(it.bookId, it.anchorJson, it.selectedText, it.location, it.sourceHash, it.type) } ?: draft
    if (currentDraft != null) AnnotationEditor(currentDraft, editing, onDismiss = { edit = null; onDraft(null) }, onSave = { note, color ->
        scope.launch {
            try {
                if (editing == null) container.annotations.save(currentDraft, note, color)
                else container.database.annotationDao().update(editing.copy(note = note, color = color, type = if (editing.type == "BOOKMARK") "BOOKMARK" else if (note.isBlank()) "HIGHLIGHT" else "NOTE", updatedAt = System.currentTimeMillis()))
                edit = null; onDraft(null)
            } catch (e: Exception) { error = "标注保存失败：${e.message}" }
        }
    })
    delete?.let { a -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除这条标注？") },
        text = { Text("删除后无法撤销，原文不受影响。") }, confirmButton = { TextButton(onClick = { scope.launch {
            try { container.database.annotationDao().delete(a.id); delete = null } catch (e: Exception) { error = "删除失败：${e.message}" }
        } }) { Text("删除") } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("知道了") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationCenter(container: MoyueContainer, onBack: () -> Unit, onOpen: (Long, String) -> Unit) {
    val items by container.database.annotationDao().observeAll().collectAsState(initial = emptyList())
    var bookId by remember { mutableStateOf<Long?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<AnnotationEntity?>(null) }
    var delete by remember { mutableStateOf<AnnotationEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val filtered = items.filter { (bookId == null || it.annotation.bookId == bookId) && (type == null || it.annotation.type == type) && (color == null || it.annotation.color == color) &&
        (query.isBlank() || it.bookTitle.contains(query, true) || it.annotation.selectedText.contains(query, true) || it.annotation.note.contains(query, true)) }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if (uri != null) scope.launch {
        try { withContext(Dispatchers.IO) { requireNotNull(container.applicationContext.contentResolver.openOutputStream(uri, "wt")).use { it.write(exportText.toByteArray(Charsets.UTF_8)) } }; snackbar.showSnackbar("摘录已导出") }
        catch (e: Exception) { snackbar.showSnackbar("导出失败：${e.message}") }
    } }
    Scaffold(topBar = { TopAppBar(title = { Text("摘录 · ${filtered.size}") }, navigationIcon = { TextButton(onBack) { Text("返回") } },
        actions = { TextButton(enabled = filtered.isNotEmpty(), onClick = { exportText = container.annotations.export(filtered); export.launch("墨阅摘录.md") }) { Text("导出") } }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索摘录、笔记或书名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(bookId == null, { bookId = null }, label = { Text("所有书籍") })
                items.distinctBy { it.annotation.bookId }.forEach { item -> FilterChip(bookId == item.annotation.bookId, { bookId = item.annotation.bookId }, label = { Text(item.bookTitle, maxLines = 1) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(type == null, { type = null }, label = { Text("全部类型") })
                listOf("BOOKMARK", "HIGHLIGHT", "NOTE").forEach { key -> FilterChip(type == key, { type = key }, label = { Text(typeLabel(key)) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(color == null, { color = null }, label = { Text("全部颜色") })
                annotationColors.forEach { (key, name) -> FilterChip(color == key, { color = key }, label = { Text(name) }) }
            }
            if (filtered.isEmpty()) Text("暂无匹配的摘录", Modifier.padding(24.dp))
            LazyColumn(Modifier.weight(1f)) { items(filtered, key = { it.annotation.id }) { item -> AnnotationRow(item.annotation, item.bookTitle,
                { onOpen(item.annotation.bookId, item.annotation.anchorJson) }, { edit = item.annotation }, { delete = item.annotation }) } }
        }
    }
    edit?.let { a -> AnnotationEditor(AnnotationDraft(a.bookId, a.anchorJson, a.selectedText, a.location, a.sourceHash, a.type), a, { edit = null }) { note, value -> scope.launch {
        try { container.database.annotationDao().update(a.copy(note = note, color = value, type = if (a.type == "BOOKMARK") "BOOKMARK" else if (note.isBlank()) "HIGHLIGHT" else "NOTE", updatedAt = System.currentTimeMillis())); edit = null }
        catch (e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
    } } }
    delete?.let { a -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除这条标注？") }, confirmButton = { TextButton({ scope.launch {
        try { container.database.annotationDao().delete(a.id); delete = null } catch (e: Exception) { snackbar.showSnackbar("删除失败：${e.message}") }
    } }) { Text("删除") } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearch(container: MoyueContainer, onBack: () -> Unit, onOpen: (Long, String?) -> Unit) {
    val books by container.database.bookDao().observeAll().collectAsState(initial = emptyList())
    val annotations by container.database.annotationDao().observeAll().collectAsState(initial = emptyList())
    val states by container.database.searchDao().observeStates().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("全部") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var limit by remember { mutableIntStateOf(40) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, limit, states) {
        busy = true; error = null
        try { delay(300); hits = if (query.isBlank()) emptyList() else container.database.searchDao().search(SearchTerms.query(query), query.trim().take(120), limit + 1, 0) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "搜索失败，请重建索引后重试" }
        finally { busy = false }
    }
    val q = query.trim().take(120)
    val matchedBooks = if (q.isEmpty()) emptyList() else books.filter { it.title.contains(q, true) || it.author.orEmpty().contains(q, true) }
    val matchedAnnotations = if (q.isEmpty()) emptyList() else annotations.filter { it.annotation.selectedText.contains(q, true) || it.annotation.note.contains(q, true) }
    Scaffold(topBar = { TopAppBar(title = { Text("全局搜索") }, navigationIcon = { TextButton(onBack) { Text("返回") } }, actions = { TextButton({ container.searchIndexer.refresh() }) { Text("重建索引") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it.take(120); limit = 40 }, singleLine = true, placeholder = { Text("搜索书名、作者、正文或摘录") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("全部", "书籍", "正文", "摘录").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) } }
            val pending = states.count { it.status == "INDEXING" }
            Text(if (pending > 0) "正在建立 $pending 本正文索引…" else "已索引 ${states.count { it.status in setOf("READY", "PARTIAL") }} 本；网页仅搜索缓存正文", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            val special = states.filter { it.status in setOf("SCAN", "LOCKED", "ERROR", "PARTIAL") }
            if (q.isEmpty()) special.take(4).forEach { s -> Text("${books.firstOrNull { it.id == s.bookId }?.title.orEmpty()}：${s.message}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f)) {
                if (tab in setOf("全部", "书籍")) items(matchedBooks, key = { "book:${it.id}" }) { book -> ListItem(headlineContent = { Text(book.title) }, supportingContent = { Text(book.author.orEmpty()) }, modifier = Modifier.clickable { onOpen(book.id, null) }) }
                if (tab in setOf("全部", "正文")) items(hits.take(limit), key = { "text:${it.chunk.id}" }) { hit ->
                    val index = hit.chunk.text.indexOf(q, ignoreCase = true).coerceAtLeast(0)
                    ListItem(headlineContent = { Text("${hit.bookTitle} · ${hit.chunk.location}") }, supportingContent = { Text(hit.chunk.text.substring((index - 35).coerceAtLeast(0), (index + q.length + 80).coerceAtMost(hit.chunk.text.length)), maxLines = 4) }, modifier = Modifier.clickable {
                        val a = JSONObject(hit.chunk.anchorJson)
                        val offset = a.optInt("charOffset") + index
                        val quote = hit.chunk.text.substring(index, (index + q.length).coerceAtMost(hit.chunk.text.length))
                        a.put("charOffset", offset).put("quote", quote).put("search", true).put("endBlock", a.optInt("blockIndex")).put("endOffset", offset + quote.length).put("hash", hit.chunk.sourceHash)
                        onOpen(hit.chunk.bookId, a.toString())
                    })
                }
                if (tab in setOf("全部", "摘录")) items(matchedAnnotations, key = { "annotation:${it.annotation.id}" }) { item -> ListItem(headlineContent = { Text("${item.bookTitle} · ${item.annotation.location}") }, supportingContent = { Text(item.annotation.note.ifBlank { item.annotation.selectedText }, maxLines = 4) }, modifier = Modifier.clickable { onOpen(item.annotation.bookId, item.annotation.anchorJson) }) }
                if (hits.size > limit && tab in setOf("全部", "正文")) item { TextButton({ limit += 40 }, Modifier.fillMaxWidth()) { Text("加载更多正文结果") } }
                if (q.isNotEmpty() && !busy && hits.isEmpty() && matchedBooks.isEmpty() && matchedAnnotations.isEmpty()) item { Text("未找到匹配。未缓存章节与扫描页没有可搜索正文。", Modifier.padding(24.dp)) }
            }
        }
    }
}
