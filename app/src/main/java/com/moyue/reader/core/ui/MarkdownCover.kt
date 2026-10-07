package com.moyue.reader.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One document cover for the shelf, recent card and details, including already imported MDs. */
@Composable
fun MarkdownCover(title: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(MoyueRadius.coverShape).semantics { contentDescription = "Markdown 文档：$title" }) {
        val ratio = maxWidth / 600.dp
        val density = LocalDensity.current
        val titleSize = with(density) { ((if (title.length > 20) 38 else 46) * ratio).dp.toSp() }
        val lineHeight = with(density) { (58 * ratio).dp.toSp() }
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / 600f, size.height / 840f, pivot = Offset.Zero) {
                drawRect(MoyueColors.AccentSoft, size = Size(600f, 840f))
                drawRoundRect(MoyueColors.Card, Offset(42f, 40f), Size(516f, 756f), CornerRadius(18f))
                drawPath(Path().apply { moveTo(458f, 40f); lineTo(558f, 140f); lineTo(458f, 140f); close() }, MoyueColors.Accent.copy(alpha = .12f))
                drawRoundRect(MoyueColors.Accent, Offset(78f, 78f), Size(158f, 124f), CornerRadius(14f))
                for (y in listOf(550f, 596f, 642f)) {
                    drawLine(MoyueColors.Accent.copy(alpha = .14f), Offset(78f, y), Offset(if (y == 642f) 356f else 506f, y), 9f)
                }
            }
        }
        Text("M↓", color = MoyueColors.Card, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
            fontSize = with(density) { (66 * ratio).dp.toSp() },
            lineHeight = with(density) { (80 * ratio).dp.toSp() },
            modifier = Modifier.padding(start = (96 * ratio).dp, top = (86 * ratio).dp))
        Text(title.ifBlank { "未命名文档" }, color = MoyueColors.AccentDeep, fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold, fontSize = titleSize, lineHeight = lineHeight,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (78 * ratio).dp, end = (78 * ratio).dp, top = (282 * ratio).dp))
        Text("MARKDOWN", color = MoyueColors.Accent, fontSize = with(density) { (25 * ratio).dp.toSp() },
            lineHeight = with(density) { (32 * ratio).dp.toSp() },
            fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = (78 * ratio).dp, bottom = (82 * ratio).dp))
    }
}
