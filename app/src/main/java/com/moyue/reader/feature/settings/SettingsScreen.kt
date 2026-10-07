package com.moyue.reader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.ui.MoyueSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: ReaderPreferences,
    onPreferences: (ReaderPreferences) -> Unit,
    onClearCache: () -> Unit,
    onBookshelf: () -> Unit,
) {
    val context=LocalContext.current
    var showLicenses by remember {mutableStateOf(false)}
    val pdfLicense=remember {context.assets.open("pdfjs/LICENSE").bufferedReader().use {it.readText()}}
    val imageLicense=remember {context.assets.open("licenses/ZoomImage-1.4.0.txt").bufferedReader().use {it.readText()}}
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("设置") },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                NavigationBarItem(false, onBookshelf, { Icon(Icons.Default.Home, null) }, label = { Text("书架") })
                NavigationBarItem(true, {}, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle("导入设置")
            SettingSwitch("自动检测编码", "TXT 导入时优先自动识别字符编码", preferences.autoDetectEncoding) {
                onPreferences(preferences.copy(autoDetectEncoding = it))
            }
            SettingSwitch("自动识别章节", "根据常见章节标题建立目录", preferences.autoDetectChapters) {
                onPreferences(preferences.copy(autoDetectChapters = it))
            }
            SettingSwitch("自动生成封面", "没有封面时生成稳定的低饱和纸张封面", preferences.autoGenerateCover) {
                onPreferences(preferences.copy(autoGenerateCover = it))
            }
            SectionDivider()
            SectionTitle("存储与隐私")
            SettingRow("清理阅读缓存", "源文件、书签和阅读记录不会删除", onClearCache)
            SettingRow("隐私说明", "所有书籍与阅读记录仅保存在本机") {}
            SectionDivider()
            SectionTitle("关于")
            SettingRow("墨阅", "本地优先 · 无账号 · 无广告") {}
            SettingRow("开源许可", "AndroidX、Kotlin、Vditor、KaTeX、PDF.js、ZoomImage、Coil") {showLicenses=true}
        }
    }
    if(showLicenses) AlertDialog(onDismissRequest={showLicenses=false},title={Text("阅读组件 · Apache-2.0")},text={Column(Modifier.verticalScroll(rememberScrollState())) {Text("PDF.js 6.4.299 / Mozilla\n\n$pdfLicense\n\nZoomImage 1.4.0 / panpf；Coil 3.2.0 / Coil Contributors（Apache-2.0）。通过公开 API 复用，未改动上游内核。\n\n$imageLicense",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton({showLicenses=false}) {Text("关闭")}})
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = MoyueSpacing.Page, end = MoyueSpacing.Page, top = MoyueSpacing.Section, bottom = MoyueSpacing.Tight),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(top = MoyueSpacing.Section),
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = MoyueSpacing.Page, vertical = MoyueSpacing.Item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = MoyueSpacing.Item)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked, onChecked)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MoyueSpacing.Page, vertical = 14.dp),
    ) {
        Text(title)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
