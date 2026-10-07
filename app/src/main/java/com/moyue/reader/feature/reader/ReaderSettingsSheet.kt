package com.moyue.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import kotlin.math.roundToInt
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.core.settings.ReaderFont
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderTheme
import com.moyue.reader.core.settings.SpacingLevel
import com.moyue.reader.core.ui.MoyueSpacing
import com.moyue.reader.core.ui.palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
    showBookNavigation: Boolean = true,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(MoyueSpacing.Section),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)

            // Theme swatches. The old row of five FilterChips said "米黄" / "淡绿" in words; showing
            // the actual paper colour is both faster to read and the point of a reading theme.
            SettingBlock("主题") {
                Row(horizontalArrangement = Arrangement.spacedBy(MoyueSpacing.Tight)) {
                    ReaderTheme.entries.forEach { theme ->
                        ThemeSwatch(
                            theme = theme,
                            selected = preferences.theme == theme && !preferences.customColors,
                            onClick = { onChange(preferences.copy(theme = theme, customColors = false)) },
                        )
                    }
                }
            }

            // Live preview: the sample text renders at the size, line height and font being chosen.
            SettingBlock("字号  ${preferences.fontSizeSp.toInt()}sp") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(readerPreviewBackground(preferences))
                        .padding(horizontal = MoyueSpacing.Item, vertical = MoyueSpacing.Item),
                ) {
                    Text(
                        "夜色渐渐笼罩城市，远处的灯光一点一点亮起。",
                        fontSize = preferences.fontSizeSp.sp,
                        lineHeight = (preferences.fontSizeSp * preferences.previewLineFactor).sp,
                        fontFamily = preferences.previewFont,
                        color = readerPreviewInk(preferences),
                    )
                }
                Slider(
                    value = preferences.fontSizeSp,
                    // Continuous range, snapped to whole sp in the callback. A discrete step grid was
                    // the wrong tool here: no step count divides 14..32 evenly, so neighbouring stops
                    // rounded to the same integer and the "18sp" label could stall while the thumb
                    // kept moving. Rounding the value itself keeps label and rendered text in step.
                    onValueChange = { onChange(preferences.copy(fontSizeSp = it.roundToInt().toFloat())) },
                    valueRange = 14f..32f,
                )
            }

            SettingBlock("字体") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ReaderFont.entries.forEachIndexed { index, font ->
                        SegmentedButton(
                            selected = preferences.fontFamily == font,
                            onClick = { onChange(preferences.copy(fontFamily = font)) },
                            shape = SegmentedButtonDefaults.itemShape(index, ReaderFont.entries.size),
                            label = { Text(font.label, fontSize = 13.sp) },
                        )
                    }
                }
            }

            SpacingRow("行距", preferences.lineSpacing) { onChange(preferences.copy(lineSpacing = it)) }
            SpacingRow("段距", preferences.paragraphSpacing) { onChange(preferences.copy(paragraphSpacing = it)) }
            SpacingRow("边距", preferences.margin) { onChange(preferences.copy(margin = it)) }

            if (showBookNavigation) SettingBlock("翻页方式") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PageMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = preferences.pageMode == mode,
                            onClick = { onChange(preferences.copy(pageMode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index, PageMode.entries.size),
                            label = { Text(if (mode == PageMode.SCROLL) "上下滚动" else "左右分页") },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ToggleSetting("段落首行空两格", preferences.indentParagraphs) { onChange(preferences.copy(indentParagraphs = it)) }
            if (showBookNavigation) ToggleSetting("章末继续翻动进入下一章", preferences.autoNextChapter) { onChange(preferences.copy(autoNextChapter = it)) }
            ToggleSetting("阅读时屏幕常亮", preferences.keepScreenOn) { onChange(preferences.copy(keepScreenOn = it)) }
            ToggleSetting("亮度跟随系统", preferences.brightness < 0f) {
                onChange(preferences.copy(brightness = if (it) -1f else .5f))
            }
            if (preferences.brightness >= 0f) {
                SettingBlock("阅读亮度") {
                    Slider(
                        value = preferences.brightness,
                        onValueChange = { onChange(preferences.copy(brightness = it)) },
                        valueRange = .05f..1f,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CustomColors(preferences, onChange)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(ReaderPreferences()) }) { Text("恢复默认") }
                Text(
                    "修改会立即保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = theme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(palette.background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("文", color = palette.ink, fontFamily = FontFamily.Serif, fontSize = 17.sp)
        }
        Text(
            theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SettingBlock(label: String, content: @Composable () -> Unit) {
    Column {
        SettingLabel(label)
        content()
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = MoyueSpacing.Tight),
    )
}

/** Label on the left, three-step choice on the right — half the height of three stacked chips. */
@Composable
private fun SpacingRow(label: String, value: SpacingLevel, onChange: (SpacingLevel) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SpacingLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = value == level,
                    onClick = { onChange(level) },
                    shape = SegmentedButtonDefaults.itemShape(index, SpacingLevel.entries.size),
                    label = { Text(level.label, fontSize = 13.sp) },
                )
            }
        }
    }
}

private val SpacingLevel.label: String get() = when (this) {
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

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Custom colours stay available but fold away behind their toggle, and the two hex fields are
 * paired with a live chip so the user can see the result instead of trusting a code.
 */
@Composable
private fun CustomColors(preferences: ReaderPreferences, onChange: (ReaderPreferences) -> Unit) {
    var background by remember(preferences.backgroundHex) { mutableStateOf(preferences.backgroundHex) }
    var ink by remember(preferences.textHex) { mutableStateOf(preferences.textHex) }
    val backgroundValid = Regex("[0-9a-fA-F]{6}").matches(background)
    val inkValid = Regex("[0-9a-fA-F]{6}").matches(ink)

    ToggleSetting("自定义阅读颜色", preferences.customColors) { onChange(preferences.copy(customColors = it)) }

    if (preferences.customColors) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(
                        if (backgroundValid) background.hexToColorOrNull() ?: Color(0xFFF7F7F5.toInt()) else Color.Transparent,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("文", color = if (inkValid) ink.hexToColorOrNull() ?: Color(0xFF222222.toInt()) else Color.Gray)
            }
            Column(Modifier.weight(1f).padding(start = MoyueSpacing.Item)) {
                Text("预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (backgroundValid && inkValid) "颜色可应用" else "请输入六位十六进制色值",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                enabled = backgroundValid && inkValid,
                onClick = {
                    // Locale.ROOT: under a Turkish locale "i".uppercase() yields "İ", which then fails
                    // the hex parse and silently falls back to the theme colour.
                    onChange(
                        preferences.copy(
                            backgroundHex = background.uppercase(java.util.Locale.ROOT),
                            textHex = ink.uppercase(java.util.Locale.ROOT),
                        ),
                    )
                },
            ) { Text("应用") }
        }
        OutlinedTextField(
            value = background,
            onValueChange = { background = it.removePrefix("#").take(6) },
            label = { Text("背景色") },
            supportingText = { Text("六位色值，如 F7F7F5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ink,
            onValueChange = { ink = it.removePrefix("#").take(6) },
            label = { Text("文字色") },
            supportingText = { Text("六位色值，如 222222") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Six hex digits with or without a leading '#'. */
private fun String.hexToColorOrNull(): Color? = runCatching {
    Color(("#" + removePrefix("#")).toColorInt())
}.getOrNull()

// --------------------------------------------------------------- preview helpers

private fun readerPreviewBackground(preferences: ReaderPreferences): Color =
    if (preferences.customColors) {
        preferences.backgroundHex.hexToColorOrNull() ?: preferences.theme.palette.background
    } else preferences.theme.palette.background

private fun readerPreviewInk(preferences: ReaderPreferences): Color =
    if (preferences.customColors) {
        preferences.textHex.hexToColorOrNull() ?: preferences.theme.palette.ink
    } else preferences.theme.palette.ink

private val ReaderPreferences.previewLineFactor: Float
    get() = when (lineSpacing) {
        SpacingLevel.COMPACT -> 1.45f
        SpacingLevel.STANDARD -> 1.7f
        SpacingLevel.WIDE -> 2.0f
    }

private val ReaderPreferences.previewFont: FontFamily
    get() = when (fontFamily) {
        ReaderFont.SYSTEM, ReaderFont.SANS -> FontFamily.SansSerif
        ReaderFont.SERIF, ReaderFont.LXGW -> FontFamily.Serif
    }
