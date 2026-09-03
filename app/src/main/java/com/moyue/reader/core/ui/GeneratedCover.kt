package com.moyue.reader.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.moyue.reader.core.storage.CoverFileGenerator
import java.io.File
import java.io.FileOutputStream

private data class CoverPalette(val sky: Color, val mountain: Color, val ground: Color)

private val palettes = listOf(
    CoverPalette(Color(0xFFF4F0E5), Color(0xFFB7C6B2), Color(0xFF647962)),
    CoverPalette(Color(0xFFE8EEEA), Color(0xFF8EA6A0), Color(0xFF425B56)),
    CoverPalette(Color(0xFFF1E9DF), Color(0xFFC0A993), Color(0xFF745F51)),
    CoverPalette(Color(0xFFE9EAEC), Color(0xFFA9ADB3), Color(0xFF5E6268)),
)

@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier) {
    val palette = palettes[(title.hashCode() and Int.MAX_VALUE) % palettes.size]
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        Canvas(Modifier.fillMaxSize()) { drawLandscape(palette, title.hashCode()) }
        Text(
            title,
            color = MoyueColors.Ink,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 16.dp),
        )
    }
}

private fun DrawScope.drawLandscape(palette: CoverPalette, seed: Int) {
    drawRect(palette.sky)
    val drift = ((seed and Int.MAX_VALUE) % 17) / 100f
    val back = Path().apply {
        moveTo(0f, size.height * .58f)
        lineTo(size.width * (.28f + drift), size.height * .40f)
        lineTo(size.width * .58f, size.height * .61f)
        lineTo(size.width * .78f, size.height * .48f)
        lineTo(size.width, size.height * .64f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(back, palette.mountain)
    val front = Path().apply {
        moveTo(0f, size.height * .77f)
        lineTo(size.width * .36f, size.height * .58f)
        lineTo(size.width * .64f, size.height * .74f)
        lineTo(size.width, size.height * .56f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(front, palette.ground)
    drawCircle(Color.White.copy(alpha = .65f), size.minDimension * .035f, Offset(size.width * .72f, size.height * .25f))
}

class GeneratedCoverFileWriter : CoverFileGenerator {
    override fun generate(title: String, destination: File): File {
        destination.parentFile?.mkdirs()
        val palette = palettes[(title.hashCode() and Int.MAX_VALUE) % palettes.size]
        val bitmap = createBitmap(600, 840)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, 840f,
            intArrayOf(palette.sky.toArgb(), palette.mountain.toArgb(), palette.ground.toArgb()),
            floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 600f, 840f, paint)
        paint.shader = null
        paint.color = AndroidColor.rgb(37, 37, 37)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 48f
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        title.chunked(8).take(3).forEachIndexed { index, line -> canvas.drawText(line, 300f, 150f + index * 62f, paint) }
        FileOutputStream(destination).use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
        bitmap.recycle()
        return destination
    }
}

private fun Color.toArgb(): Int = AndroidColor.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
