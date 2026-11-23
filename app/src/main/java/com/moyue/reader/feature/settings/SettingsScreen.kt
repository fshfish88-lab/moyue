package com.moyue.reader.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.ui.*
import com.moyue.reader.feature.reader.ReaderSettingsSheet
import com.moyue.reader.feature.update.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(preferences: ReaderPreferences, onPreferences: (ReaderPreferences) -> Unit,
    onClearCache: () -> Unit, onBookshelf: () -> Unit, updateState: AppUpdateState,
    onAutomaticUpdates: (Boolean) -> Unit, onCheckUpdates: () -> Unit, onOpenUpdate: () -> Unit,
    onAnnotations: () -> Unit = {}, onLibrary: () -> Unit = {}) {
    val context = LocalContext.current
    var section by remember { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    val pdfLicense = remember { context.assets.open("pdfjs/LICENSE").bufferedReader().use { it.readText() } }
    val imageLicense = remember { context.assets.open("licenses/ZoomImage-1.4.0.txt").bufferedReader().use { it.readText() } }
    BackHandler(section != null) { section = null }
    Scaffold(topBar = { if (section != null) PaperTopBar(section!!, { section = null }) else TopAppBar(title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { if (section == null) PaperNavigation(2, onBookshelf, onAnnotations, {}) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            when (section) {
                "导入偏好" -> {
                    Text("导入时，自动做好这些事。", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingSwitch("自动检测编码", "TXT 导入时识别字符编码", preferences.autoDetectEncoding) { onPreferences(preferences.copy(autoDetectEncoding = it)) }
                    SettingSwitch("自动识别章节", "根据常见章节标题建立目录", preferences.autoDetectChapters) { onPreferences(preferences.copy(autoDetectChapters = it)) }
                    SettingSwitch("自动生成封面", "没有封面时生成本地纸张封面", preferences.autoGenerateCover) { onPreferences(preferences.copy(autoGenerateCover = it)) }
                    Text("导入后保存应用内副本。\n外部原始文件不受影响。", Modifier.padding(top = 28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "软件更新" -> {
                    PaperSection("当前版本 V${com.moyue.reader.BuildConfig.VERSION_NAME}")
                    SettingSwitch("自动检查更新", "每日最多一次；下载与安装由你确认", updateState.automatic, onAutomaticUpdates)
                    PaperCard { PaperRow("检查更新", updateState.message, PaperIcons.Refresh, onClick = onCheckUpdates)
                        if (updateState.release != null) PaperRow(if (updateState.phase == UpdatePhase.READY) "安装更新" else "下载更新",
                            "V${updateState.release.versionName}", PaperIcons.Download, onClick = onOpenUpdate) }
                }
                else -> {
                    Spacer(Modifier.height(20.dp))
                    PaperCard { PaperRow("墨阅", "本地优先 · 无账号 · 无广告", PaperIcons.Book, onClick = onLibrary) }
                    PaperSection("阅读与导入")
                    PaperCard {
                        PaperRow("阅读设置", "主题、字体与排版", PaperIcons.Book) { reader = true }
                        PaperRow("导入偏好", "编码、章节与封面", PaperIcons.Download) { section = "导入偏好" }
                    }
                    PaperSection("存储与其他")
                    PaperCard {
                        PaperRow("书库概览", "本机书籍、书架与收藏", PaperIcons.Grid, onClick = onLibrary)
                        PaperRow("清理阅读缓存", "保留源文件、书签和阅读记录", PaperIcons.Refresh) { clear = true }
                        PaperRow("软件更新", "当前版本 V${com.moyue.reader.BuildConfig.VERSION_NAME}", PaperIcons.Download) { section = "软件更新" }
                        PaperRow("隐私说明", "本地数据与网络访问", PaperIcons.File) { privacy = true }
                        PaperRow("开源许可", "阅读与文档组件", PaperIcons.File) { licenses = true }
                    }
                }
            }
        }
    }
    if (reader) ReaderSettingsSheet(preferences, onPreferences, { reader = false })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理阅读缓存？") },
        text = { Text("缓存可重新生成，源文件、书签、笔记和阅读记录都会保留。") },
        confirmButton = { Button({ clear = false; onClearCache() }) { Text("清理缓存") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("隐私说明") },
        text = { Text("书籍与阅读记录保存在本机。网页导入和软件更新会访问相应网络服务。没有账号或云端同步。") }, confirmButton = { TextButton({ privacy = false }) { Text("关闭") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("阅读组件 · Apache-2.0") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("PDF.js 6.4.299 / Mozilla\n\n$pdfLicense\n\nZoomImage 1.4.0 / panpf；Coil 3.2.0 / Coil Contributors（Apache-2.0）。\n\n$imageLicense", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton({ licenses = false }) { Text("关闭") } })
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    PaperCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onChecked, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.secondary))
        }
    }
}
