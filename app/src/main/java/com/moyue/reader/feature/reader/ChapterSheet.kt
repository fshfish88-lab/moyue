package com.moyue.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.ui.MoyueSpacing
import kotlinx.coroutines.launch

@Composable
fun ChapterSheet(chapters: List<ChapterEntity>, currentIndex: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit,
    catalogMessage:String?=null, refreshing:Boolean=false,onRefresh:(()->Unit)?=null,onSource:(()->Unit)?=null,onDiagnostic:(()->Unit)?=null) {
    val current = currentIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
    var target by remember(chapters.size) { mutableFloatStateOf(current.toFloat()) }
    var jump by remember { mutableStateOf("") }
    val list = rememberLazyListState(current)
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal=MoyueSpacing.Page,vertical=MoyueSpacing.Item)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("目录 · 共 ${chapters.size} 章",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=onDismiss) { Text("关闭目录") }
                }
                if(catalogMessage!=null)Text(catalogMessage,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={target=current.toFloat();scope.launch {list.scrollToItem(current)}}) {Text("定位当前章")}
                    if(onRefresh!=null)TextButton(onClick=onRefresh,enabled=!refreshing) {Text(if(refreshing)"正在刷新目录…" else "刷新目录")}
                }
                if(onSource!=null || onDiagnostic!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    if(onSource!=null)TextButton(onClick=onSource,enabled=!refreshing) {Text("目录来源")}
                    if(onDiagnostic!=null)TextButton(onClick=onDiagnostic,enabled=!refreshing) {Text("导出诊断")}
                }
                Text("拖动快速定位第 ${target.toInt()+1} 章",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(chapters.size>1)Slider(value=target.coerceIn(0f,chapters.lastIndex.toFloat()),onValueChange={target=it},
                    valueRange=0f..chapters.lastIndex.toFloat(),onValueChangeFinished={scope.launch {list.scrollToItem(target.toInt())}})
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(jump,{jump=it.filter(Char::isDigit).take(6)},label={Text("输入章序号")},singleLine=true,modifier=Modifier.weight(1f))
                    TextButton(enabled=jump.toIntOrNull() in 1..chapters.size,onClick={val index=jump.toInt()-1;target=index.toFloat();scope.launch {list.scrollToItem(index)}}) {Text("定位")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(top=MoyueSpacing.Tight))
                LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth()) {
                    items(chapters,key=ChapterEntity::id) {chapter->
                        val active=chapter.chapterIndex==currentIndex
                        Row(Modifier.fillMaxWidth().clickable(enabled=!refreshing) {onSelect(chapter.chapterIndex)}.padding(horizontal=MoyueSpacing.Tight,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(18.dp).background(if(active)MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(2.dp)))
                            Text(chapter.title,fontWeight=if(active)FontWeight.Medium else FontWeight.Normal,
                                color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier=Modifier.padding(start=MoyueSpacing.Item))
                        }
                    }
                }
            }
        }
    }
}
