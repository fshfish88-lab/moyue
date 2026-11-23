package com.moyue.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.database.AnnotationEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.ui.*
import com.moyue.reader.core.ui.PaperSlider as Slider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(chapters: List<ChapterEntity>, currentIndex: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit,
    catalogMessage: String? = null, refreshing: Boolean = false, onRefresh: (() -> Unit)? = null,
    onSource: (() -> Unit)? = null, onDiagnostic: (() -> Unit)? = null,
    annotations: List<AnnotationEntity> = emptyList(), onAnnotation: (AnnotationEntity) -> Unit = {}, onManageAnnotations: () -> Unit = {}) {
    val current = currentIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
    var tab by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var target by remember(current, chapters.size) { mutableFloatStateOf(current.toFloat()) }
    var jump by remember { mutableStateOf("") }
    val list = rememberLazyListState(current)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false, dragHandle = null, sheetMaxWidth = androidx.compose.ui.unit.Dp.Infinity,
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("目录 · 共 ${chapters.size} 章", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onDismiss) { Icon(PaperIcons.Close, "关闭目录") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("目录", "书签", "标注", "笔记").forEachIndexed { index, label -> PaperChip(tab == index, { tab = index }, { Text(label) }) }
            }
            if (tab == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = chapters.isNotEmpty(), onClick = {
                        target = current.toFloat()
                        scope.launch { list.scrollToItem(current) }
                    }) { Text("定位当前章") }
                    TextButton({ advanced = !advanced }) { Text(if (advanced) "收起定位" else "跳转与目录选项") }
                }
                catalogMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (advanced) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        onRefresh?.let { TextButton(it, enabled = !refreshing) { Text(if (refreshing) "正在刷新目录…" else "刷新目录") } }
                        onSource?.let { TextButton(it, enabled = !refreshing) { Text("目录来源") } }
                        onDiagnostic?.let { TextButton(it, enabled = !refreshing) { Text("导出诊断") } }
                    }
                    if (chapters.size > 1) Slider(target.coerceIn(0f, chapters.lastIndex.toFloat()), { target = it },
                        valueRange = 0f..chapters.lastIndex.toFloat(), onValueChangeFinished = { scope.launch { list.scrollToItem(target.toInt()) } })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(6) }, label = { Text("输入章序号") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = jump.toIntOrNull() in 1..chapters.size, onClick = { target = (jump.toInt() - 1).toFloat(); scope.launch { list.scrollToItem(target.toInt()) } }) { Text("定位") }
                    }
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(chapters, key = ChapterEntity::id) { chapter ->
                        val active = chapter.chapterIndex == currentIndex
                        Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable(enabled = !refreshing) { onSelect(chapter.chapterIndex) }.padding(horizontal = 12.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chapter.title, Modifier.weight(1f), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                            Icon(if (active) PaperIcons.Book else PaperIcons.Chevron, null, Modifier.size(16.dp))
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                val type = when (tab) { 1 -> "BOOKMARK"; 2 -> "HIGHLIGHT"; else -> "NOTE" }
                val visible = annotations.filter { it.type == type }
                TextButton(onManageAnnotations) { Text("管理标注与笔记") }
                if (visible.isEmpty()) Text("暂无${listOf("目录", "书签", "标注", "笔记")[tab]}", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = AnnotationEntity::id) { annotation ->
                        PaperCard(Modifier.fillMaxWidth().clickable { onAnnotation(annotation) }) {
                            Column(Modifier.padding(18.dp)) {
                                Text(annotation.location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(annotation.note.ifBlank { annotation.selectedText.ifBlank { "位置书签" } }, Modifier.padding(top = 8.dp), maxLines = 5)
                            }
                        }
                    }
                }
            }
        }
    }
}
