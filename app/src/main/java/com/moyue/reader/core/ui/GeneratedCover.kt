package com.moyue.reader.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.core.graphics.createBitmap
import com.moyue.reader.core.storage.CoverFileGenerator
import java.io.File
import java.io.FileOutputStream

/**
 * The cover shown in the shelf grid and the cover written to disk during import.
 *
 * Both renderers walk the *same* [CoverLayout] in the same 600x840 reference space, which is the
 * point. They used to be two unrelated implementations — a Compose mountain path on screen and a
 * bitmap vertical gradient in the file — so a book looked one way in the grid and another way
 * everywhere its cover file was read.
 */
@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier, author: String? = null) {
    val layout = CoverArt.layout(title, author)
    BoxWithConstraints(modifier.clip(MoyueRadius.coverShape)) {
        // Reference units -> screen units. Everything positional in CoverLayout is expressed in the
        // 600x840 design box, so one ratio converts the whole layout. Guard the unbounded case:
        // inside a vertically scrolling parent maxHeight is Dp.Infinity, which would make ratio
        // infinite and blow up the padding math below.
        val ratio = if (maxHeight.isFinite && maxHeight > 0.dp) maxHeight / CoverArt.HEIGHT.dp else 1f
        val density = LocalDensity.current
        val titleSize = with(density) { (layout.titleSize * ratio).dp.toSp() }
        val titleLineHeight = with(density) { (layout.titleLineHeight * ratio).dp.toSp() }
        val subtitleSize = with(density) { (layout.subtitleSize * ratio).dp.toSp() }
        val ink = layout.ink.toComposeColor()

        Canvas(Modifier.fillMaxSize()) { drawCover(layout) }

        Box(Modifier.fillMaxSize()) {
            Column(
                // Position by the first line's baseline (offset by one ascent) rather than by the
                // text box top, so this matches the bitmap renderer instead of approximating it.
                Modifier
                    .fillMaxSize()
                    .padding(top = ((layout.titleBaseline - layout.titleSize) * ratio).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    CoverArt.wrap(layout.titleText, layout.titleLineCount).joinToString("\n"),
                    color = ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = titleSize,
                    lineHeight = titleLineHeight,
                    textAlign = TextAlign.Center,
                )
            }
            layout.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    color = ink.copy(alpha = .62f),
                    fontFamily = FontFamily.Serif,
                    fontSize = subtitleSize,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = (layout.subtitleY * ratio).dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- Compose renderer

internal fun DrawScope.drawCover(layout: CoverLayout) {
    // Draw in reference units so this and the bitmap renderer share one coordinate system.
    scale(size.width / CoverArt.WIDTH, size.height / CoverArt.HEIGHT, pivot = Offset.Zero) {
        drawRect(layout.palette.sky.toComposeColor(), size = Size(CoverArt.WIDTH, CoverArt.HEIGHT))

        for (ridge in layout.ridges) {
            val path = Path().apply {
                moveTo(0f, ridge.points.first().second * CoverArt.HEIGHT)
                ridge.points.drop(1).forEach { (x, y) -> lineTo(x * CoverArt.WIDTH, y * CoverArt.HEIGHT) }
                lineTo(CoverArt.WIDTH, CoverArt.HEIGHT)
                lineTo(0f, CoverArt.HEIGHT)
                close()
            }
            drawPath(path, ridge.color.toComposeColor())
        }

        for (ornament in layout.ornaments) {
            when (ornament) {
                is Ornament.Moon -> drawCircle(Color.White.copy(alpha = .66f), ornament.radius, Offset(ornament.x, ornament.y))
                is Ornament.Band -> drawRect(
                    layout.palette.ghost.toComposeColor().copy(alpha = ornament.alpha),
                    topLeft = Offset(0f, ornament.y - 5f),
                    size = Size(CoverArt.WIDTH, 10f),
                )
                is Ornament.Rule -> {
                    val half = CoverArt.WIDTH * ornament.width / 2f
                    drawLine(
                        layout.ink.toComposeColor().copy(alpha = .18f),
                        Offset(CoverArt.WIDTH / 2f - half, ornament.y),
                        Offset(CoverArt.WIDTH / 2f + half, ornament.y),
                        strokeWidth = 1.4f,
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- bitmap renderer

internal fun Canvas.drawCover(layout: CoverLayout) {
    drawRect(0f, 0f, CoverArt.WIDTH, CoverArt.HEIGHT, fill(layout.palette.sky))

    for (ridge in layout.ridges) {
        val path = AndroidPath().apply {
            moveTo(0f, ridge.points.first().second * CoverArt.HEIGHT)
            ridge.points.drop(1).forEach { (x, y) -> lineTo(x * CoverArt.WIDTH, y * CoverArt.HEIGHT) }
            lineTo(CoverArt.WIDTH, CoverArt.HEIGHT)
            lineTo(0f, CoverArt.HEIGHT)
            close()
        }
        drawPath(path, fill(ridge.color))
    }

    for (ornament in layout.ornaments) {
        when (ornament) {
            is Ornament.Moon -> drawCircle(
                ornament.x, ornament.y, ornament.radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; alpha = 168 },
            )
            is Ornament.Band -> drawRect(
                0f, ornament.y - 5f, CoverArt.WIDTH, ornament.y + 5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layout.palette.ghost.toAndroidColor(); alpha = 90 },
            )
            is Ornament.Rule -> {
                val half = CoverArt.WIDTH * ornament.width / 2f
                drawLine(
                    CoverArt.WIDTH / 2f - half, ornament.y,
                    CoverArt.WIDTH / 2f + half, ornament.y,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = layout.ink.toAndroidColor(); alpha = 46; strokeWidth = 1.4f
                    },
                )
            }
        }
    }

    drawTitles(layout)
}

private fun Canvas.drawTitles(layout: CoverLayout) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        color = layout.ink.toAndroidColor()
    }
    paint.textSize = layout.titleSize
    CoverArt.wrap(layout.titleText, layout.titleLineCount).forEachIndexed { index, line ->
        drawText(line, CoverArt.WIDTH / 2f, layout.titleBaseline + index * layout.titleLineHeight, paint)
    }
    layout.subtitle?.let { subtitle ->
        val base = layout.ink.toAndroidColor()
        paint.textSize = layout.subtitleSize
        paint.color = AndroidColor.argb(158, AndroidColor.red(base), AndroidColor.green(base), AndroidColor.blue(base))
        drawText(subtitle, CoverArt.WIDTH / 2f, layout.subtitleY, paint)
    }
}

private fun fill(ink: RGB) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toAndroidColor() }

internal fun RGB.toAndroidColor(): Int = AndroidColor.rgb(
    (r * 255f).toInt().coerceIn(0, 255),
    (g * 255f).toInt().coerceIn(0, 255),
    (b * 255f).toInt().coerceIn(0, 255),
)

private fun RGB.toComposeColor(): Color = Color(red = r, green = g, blue = b)

/**
 * Writes [CoverArt] artwork to a PNG for the imported book's cover file.
 *
 * Rendered at 2x the reference box so the shelf, the detail page and any future export all have
 * headroom without the drawn cover looking softer than the stored one.
 */
class GeneratedCoverFileWriter : CoverFileGenerator {
    override fun generate(title: String, author: String?, destination: File): File {
        destination.parentFile?.mkdirs()
        val scale = 2f
        val layout = CoverArt.layout(title, author)
        val bitmap = createBitmap((CoverArt.WIDTH * scale).toInt(), (CoverArt.HEIGHT * scale).toInt())
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.drawCover(layout)
        try {
            val written = FileOutputStream(destination).use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
            // A failed compress still leaves a file behind, and the caller trusts the returned path,
            // so a silent failure would store a truncated cover as the book's artwork.
            check(written) { "封面写入失败" }
        } finally {
            bitmap.recycle()
        }
        return destination
    }
}
