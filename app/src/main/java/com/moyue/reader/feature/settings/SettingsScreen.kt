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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.settings.ReaderPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: ReaderPreferences,
    onPreferences: (ReaderPreferences) -> Unit,
    onClearCache: () -> Unit,
    onBookshelf: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(false, onBookshelf, { Icon(Icons.Default.Home, null) }, label = { Text("书架") })
                NavigationBarItem(true, {}, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
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
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("存储与隐私")
            SettingRow("清理网页与 EPUB 缓存", "源文件和阅读记录不会删除", onClearCache)
            SettingRow("隐私说明", "所有书籍与阅读记录仅保存在本机") {}
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("关于")
            SettingRow("墨阅 V1.0", "本地优先 · 无账号 · 无广告") {}
            SettingRow("开源许可", "AndroidX、Kotlin 与 Material 组件") {}
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

@Composable private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
