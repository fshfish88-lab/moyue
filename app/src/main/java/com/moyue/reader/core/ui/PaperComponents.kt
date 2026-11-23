package com.moyue.reader.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(content = content)
    }
}

@Composable
fun PaperRow(title: String, subtitle: String? = null, icon: ImageVector? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(PaperIcons.Chevron, null, Modifier.padding(start = 10.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PaperSection(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PaperTool(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(21.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun PaperStat(value: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    PaperCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onBack) { Icon(PaperIcons.Back, "返回") } }, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

/** Thin Figma track and round thumb, retaining Material's input and accessibility behavior. */
@Composable
fun PaperSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.fillMaxWidth().height(48.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val inset = 10.dp.toPx()
            val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val x = inset + (size.width - 2 * inset) * fraction
            val y = size.height / 2
            drawLine(ink.copy(alpha = .14f), Offset(inset, y), Offset(size.width - inset, y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(ink.copy(alpha = if (enabled) .75f else .3f), Offset(inset, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0xFFFCFAF6), 5.dp.toPx(), Offset(x, y))
            drawCircle(ink.copy(alpha = .18f), 5.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
        }
        Slider(value, onValueChange, Modifier.matchParentSize(), enabled = enabled, valueRange = valueRange,
            steps = steps, onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(thumbColor = Color.Transparent, activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                disabledThumbColor = Color.Transparent, disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent, disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent))
    }
}
