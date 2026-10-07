package com.moyue.reader.core.document

import java.util.Locale

data class ImageSize(val width: Int, val height: Int) {
    init {
        require(width in 1..65535 && height in 1..65535 && width.toLong() * height <= 200_000_000) { "图片尺寸超出支持范围" }
    }
}
data class ImagePage(val name: String, val width: Int, val height: Int)
data class ImagePosition(
    val pageIndex: Int = 0, val entryName: String = "", val mode: String = "continuous",
    val fit: String = "screen", val listOffset: Float = 0f, val scale: Float = 1f,
    val centerX: Float = .5f, val centerY: Float = .5f, val rotation: Int = 0,
) {
    fun safe(pages: List<ImagePage>): ImagePosition {
        val index = pages.indexOfFirst { it.name == entryName }.takeIf { it >= 0 }
            ?: pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        fun Float.bounded(low: Float, high: Float, fallback: Float) = if (isFinite()) coerceIn(low, high) else fallback
        return copy(pageIndex = index, entryName = pages.getOrNull(index)?.name.orEmpty(),
            mode = mode.takeIf { it in setOf("single", "continuous") } ?: "continuous",
            fit = fit.takeIf { it in setOf("screen", "width") } ?: "screen",
            listOffset = listOffset.bounded(0f, 1f, 0f), scale = scale.bounded(1f, 32f, 1f),
            centerX = centerX.bounded(0f, 1f, .5f), centerY = centerY.bounded(0f, 1f, .5f),
            rotation = if (rotation % 90 == 0) ((rotation % 360) + 360) % 360 else 0)
    }
}

/** Compare digit runs by length/value, without parsing an untrusted number into an Int. */
object NaturalPageOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.lowercase(Locale.ROOT); val y = b.lowercase(Locale.ROOT)
        var i = 0; var j = 0
        while (i < x.length && j < y.length) {
            if (x[i] in '0'..'9' && y[j] in '0'..'9') {
                var endI = i; var endJ = j
                while (endI < x.length && x[endI] in '0'..'9') endI++
                while (endJ < y.length && y[endJ] in '0'..'9') endJ++
                val n = x.substring(i,endI).trimStart('0').ifEmpty { "0" }
                val m = y.substring(j,endJ).trimStart('0').ifEmpty { "0" }
                val result = n.length.compareTo(m.length).takeIf { it != 0 } ?: n.compareTo(m)
                if (result != 0) return result
                i = endI; j = endJ
            } else {
                val result = x[i].compareTo(y[j]); if (result != 0) return result
                i++; j++
            }
        }
        return (x.length - i).compareTo(y.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
