package com.moyue.reader.feature.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.moyue.reader.core.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderImage(block: ContentBlock.Image, paged: Boolean = false) {
    val decoded by produceState<Result<android.graphics.Bitmap>?>(null, block.localPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(block.localPath, bounds)
                val options = BitmapFactory.Options()
                while (bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > 2048 || bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > 2048) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                requireNotNull(BitmapFactory.decodeFile(block.localPath, options)) { "图片文件缺失或格式不支持" }
            }
        }
    }
    val bitmap = decoded?.getOrNull()
    val modifier = if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), block.description ?: "EPUB 插图", contentScale = ContentScale.Fit,
            modifier = if (paged) modifier else modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height))
    } else Box(if (paged) modifier else modifier.height(160.dp), contentAlignment = Alignment.Center) {
        if (decoded == null) CircularProgressIndicator() else Text("${block.description ?: "插图"}：图片文件缺失或格式不支持")
    }
}
