package com.moyue.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.core.settings.ReaderFont
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderTheme
import com.moyue.reader.core.settings.SpacingLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            SettingLabel("主题")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = preferences.theme == theme,
                        onClick = { onChange(preferences.copy(theme = theme)) },
                        label = { Text(theme.label) },
                    )
                }
            }
            SettingLabel("字号  ${preferences.fontSizeSp.toInt()}sp")
            Slider(
                value = preferences.fontSizeSp,
                onValueChange = { onChange(preferences.copy(fontSizeSp = it)) },
                valueRange = 14f..32f,
                steps = 17,
            )
            SettingLabel("字体")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = preferences.fontFamily == font,
                        onClick = { onChange(preferences.copy(fontFamily = font)) },
                        label = { Text(font.label) },
                    )
                }
            }
            SpacingSelector("行距", preferences.lineSpacing) { onChange(preferences.copy(lineSpacing = it)) }
            SpacingSelector("段距", preferences.paragraphSpacing) { onChange(preferences.copy(paragraphSpacing = it)) }
            SpacingSelector("边距", preferences.margin) { onChange(preferences.copy(margin = it)) }
            SettingLabel("翻页方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.pageMode == mode,
                        onClick = { onChange(preferences.copy(pageMode = mode)) },
                        label = { Text(if (mode == PageMode.SCROLL) "上下滚动" else "左右分页") },
                    )
                }
            }
            Text("修改会立即预览并自动保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        }
    }
}

@Composable private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SpacingSelector(label: String, value: SpacingLevel, onChange: (SpacingLevel) -> Unit) {
    Column {
        SettingLabel(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpacingLevel.entries.forEach { level ->
                FilterChip(
                    selected = value == level,
                    onClick = { onChange(level) },
                    label = { Text(level.label) },
                )
            }
        }
    }
}

private val SpacingLevel.label get() = when (this) {
    SpacingLevel.COMPACT -> "紧凑"
    SpacingLevel.STANDARD -> "标准"
    SpacingLevel.WIDE -> "宽松"
}

private val ReaderTheme.label: String get() = when (this) {
    ReaderTheme.DAY -> "日间"
    ReaderTheme.SEPIA -> "米黄"
    ReaderTheme.GREEN -> "淡绿"
    ReaderTheme.GRAY -> "灰色"
    ReaderTheme.NIGHT -> "夜间"
}

private val ReaderFont.label: String get() = when (this) {
    ReaderFont.SYSTEM -> "系统"
    ReaderFont.SERIF -> "宋体"
    ReaderFont.SANS -> "黑体"
    ReaderFont.LXGW -> "文楷"
}
