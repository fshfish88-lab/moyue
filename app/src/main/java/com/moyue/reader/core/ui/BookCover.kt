package com.moyue.reader.core.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.moyue.reader.core.model.SourceType

/**
 * Decoded cover bitmaps, keyed by path.
 *
 * A shelf tile scrolls in and out constantly, and without a cache every pass re-decoded the file
 * from disk. Sized by byte count rather than entry count, because real EPUB covers and generated
 * ones differ by orders of magnitude.
 */
private object CoverCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()

    val bitmaps = object : LruCache<String, android.graphics.Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
}

/**
 * A book cover: the real cover file when the book has one, otherwise generated artwork.
 *
 * The shelf previously always drew [GeneratedCover], so EPUB covers never appeared even though
 * import had already stored them — the file sat on disk unused.
 */
@Composable
fun BookCover(
    title: String,
    author: String?,
    coverPath: String?,
    modifier: Modifier = Modifier,
    sourceType: SourceType = SourceType.TXT,
) {
    if (sourceType == SourceType.MARKDOWN) {
        MarkdownCover(title, modifier)
        return
    }
    val decoded by produceState<android.graphics.Bitmap?>(null, coverPath) {
        value = coverPath?.let { path ->
            val cached = CoverCache.bitmaps.get(path)
            if (cached != null) cached
            else withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeFile(path, options)?.also { CoverCache.bitmaps.put(path, it) }
                }.getOrNull()
            }
        }
    }

    val bitmap = decoded
    if (bitmap != null) {
        Box(modifier.clip(MoyueRadius.coverShape)) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        GeneratedCover(title, modifier, author)
    }
}

/** Powers of two, capping the decoded cover near a 1024px long edge. */
private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    var longEdge = maxOf(width, height)
    while (longEdge / 2 >= 1024) {
        sample *= 2
        longEdge /= 2
    }
    return sample
}
