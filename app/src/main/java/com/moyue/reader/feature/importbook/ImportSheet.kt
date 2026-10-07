package com.moyue.reader.feature.importbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.ui.MoyueSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    initialUrl: String = "",
    preview: WebImportPreview?,
    webBusy: Boolean,
    webError: String?,
    onPickFile: () -> Unit,
    onAnalyzeWeb: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onAddWeb: () -> Unit,
    onDismiss: () -> Unit,
    onNewMarkdown: () -> Unit = {},
    onPickAnyFile: () -> Unit = onPickFile,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Tight),
            verticalArrangement = Arrangement.spacedBy(MoyueSpacing.Item),
        ) {
            Text("添加内容", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onPickFile).padding(vertical = 14.dp),
            ) {
                Text("导入文件", fontWeight = FontWeight.Medium)
                Text("支持 TXT / EPUB / Markdown / PDF / 图片 / CBZ / 图片 ZIP，保存应用内副本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onNewMarkdown) { Text("新建 Markdown") }
                TextButton(onClick = onPickAnyFile) { Text("显示所有文件") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("导入网页", fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("网页地址") },
                placeholder = { Text("https://example.com/novel/1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onAnalyzeWeb(url) }, enabled = url.isNotBlank() && !webBusy, modifier = Modifier.fillMaxWidth()) {
                if (webBusy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                Text("导入书架并阅读")
            }
            OutlinedButton(onClick = { onOpenBrowser(url) }, enabled = url.startsWith("http://") || url.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                Text("在网页中打开")
            }
            webError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            preview?.let {
                Text("解析结果", style = MaterialTheme.typography.titleMedium)
                Text(it.readable.title, fontWeight = FontWeight.Medium)
                Text(
                    "正文 ${it.readable.text.count { character -> !character.isWhitespace() }} 字",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(it.readable.text, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (it.readable.removedItems.isEmpty()) "已提取纯正文" else "已移除：${it.readable.removedItems.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("暂不保存") }
                    Button(onClick = onAddWeb, modifier = Modifier.padding(start = 12.dp)) { Text("加入书架") }
                }
            }
            Text("自动识别章节目录，正文按需加载；网站未提供完整目录时保留可识别的章节链接。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        }
    }
}
