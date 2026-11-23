package com.moyue.reader.feature.importbook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(initialUrl: String = "", webBusy: Boolean, webError: String?, onPickFile: () -> Unit,
    onAnalyzeWeb: (String) -> Unit, onOpenBrowser: (String) -> Unit, onDismiss: () -> Unit, onNewMarkdown: () -> Unit = {}) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var shareHelp by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (webError == null) "添加内容" else "导入网页", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onDismiss) { Icon(PaperIcons.Close, "关闭导入") }
            }
            if (webError == null) {
                Text("把想读的内容，留在身边。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PaperCard { PaperRow("导入文件", "TXT / EPUB / MD / PDF / 图片 / CBZ / ZIP", PaperIcons.File, !webBusy, onPickFile) }
                PaperCard { PaperRow("新建 Markdown", "记录灵感，立即编辑", PaperIcons.Edit, !webBusy, onNewMarkdown) }
                PaperCard { PaperRow("从其他应用分享", "在系统分享中选择“墨阅”", PaperIcons.Share, !webBusy) { shareHelp = true } }
                PaperSection("导入网页")
            }
            OutlinedTextField(url, { url = it }, label = { Text("网页地址") }, placeholder = { Text("https://example.com/novel/1") },
                singleLine = true, enabled = !webBusy, leadingIcon = { Icon(PaperIcons.Globe, null, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth())
            if (webError != null) Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PaperIcons.Warning, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                Text("暂时无法导入", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
                Text(webError, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button({ onAnalyzeWeb(url) }, enabled = url.isNotBlank() && !webBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (webBusy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text(if (webBusy) "正在解析…" else if (webError != null) "重新解析" else "导入书架并阅读")
            }
            FilledTonalButton({ onOpenBrowser(url) }, enabled = !webBusy && (url.startsWith("http://") || url.startsWith("https://")), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("在网页中打开") }
            Text("自动识别章节目录，正文按需加载。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (shareHelp) AlertDialog(onDismissRequest = { shareHelp = false }, title = { Text("从其他应用分享") },
        text = { Text("在文件管理器、浏览器或其他应用中选择文件或链接，点击系统“分享”，再选择“墨阅”。") },
        confirmButton = { TextButton({ shareHelp = false }) { Text("知道了") } })
}
