package com.moyue.reader.feature.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.moyue.reader.R
import com.moyue.reader.core.settings.ReaderFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single typeface shared by measurement, the native reader and settings preview. */
internal object ReaderFonts {
    @Volatile private var wenkai: Typeface? = null
    fun cached(font: ReaderFont): Typeface? = when (font) {
        ReaderFont.LXGW -> wenkai
        ReaderFont.SERIF -> Typeface.SERIF
        else -> Typeface.DEFAULT
    }
    suspend fun load(context: Context, font: ReaderFont): Typeface = cached(font) ?: withContext(Dispatchers.IO) {
        synchronized(this@ReaderFonts) {
            wenkai ?: requireNotNull(ResourcesCompat.getFont(context.applicationContext, R.font.lxgw_wenkai_regular)).also { wenkai = it }
        }
    }
}

@Composable
internal fun rememberReaderTypeface(font: ReaderFont): Typeface? {
    val context = LocalContext.current
    val face by produceState(ReaderFonts.cached(font), font) {
        value = ReaderFonts.cached(font)
        value = ReaderFonts.load(context, font)
    }
    // Never render a previous font for the first frame after changing the selection.
    return ReaderFonts.cached(font) ?: face?.takeIf { font != ReaderFont.LXGW }
}
