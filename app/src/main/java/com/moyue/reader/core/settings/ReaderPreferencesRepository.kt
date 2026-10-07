package com.moyue.reader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore(name = "reader_preferences")

class ReaderPreferencesRepository(private val context: Context) {
    private object Keys {
        val comicMode = stringPreferencesKey("comic_mode")
        val imageFit = stringPreferencesKey("image_fit")
        val pdfMode = stringPreferencesKey("pdf_mode")
        val pdfFit = stringPreferencesKey("pdf_fit")
        val pdfInvert = booleanPreferencesKey("pdf_invert")
        val customColors = booleanPreferencesKey("custom_colors")
        val backgroundHex = stringPreferencesKey("background_hex")
        val textHex = stringPreferencesKey("text_hex")
        val indent = booleanPreferencesKey("indent")
        val autoNext = booleanPreferencesKey("auto_next")
        val keepOn = booleanPreferencesKey("keep_on")
        val brightness = floatPreferencesKey("brightness")
        val theme = stringPreferencesKey("theme")
        val fontSize = floatPreferencesKey("font_size")
        val font = stringPreferencesKey("font")
        val lineSpacing = stringPreferencesKey("line_spacing")
        val paragraphSpacing = stringPreferencesKey("paragraph_spacing")
        val margin = stringPreferencesKey("margin")
        val pageMode = stringPreferencesKey("page_mode")
        val detectEncoding = booleanPreferencesKey("detect_encoding")
        val detectChapters = booleanPreferencesKey("detect_chapters")
        val generateCover = booleanPreferencesKey("generate_cover")
    }

    val preferences: Flow<ReaderPreferences> = context.readerDataStore.data.map { values ->
        ReaderPreferences(
            comicPageMode = values[Keys.comicMode] ?: "continuous",
            imageFit = values[Keys.imageFit] ?: "screen",
            pdfPageMode = values[Keys.pdfMode] ?: "continuous",
            pdfFit = values[Keys.pdfFit] ?: "page-width",
            pdfInvert = values[Keys.pdfInvert] ?: false,
            customColors = values[Keys.customColors] ?: false,
            backgroundHex = values[Keys.backgroundHex] ?: "F7F7F5",
            textHex = values[Keys.textHex] ?: "222222",
            indentParagraphs = values[Keys.indent] ?: true,
            autoNextChapter = values[Keys.autoNext] ?: true,
            keepScreenOn = values[Keys.keepOn] ?: false,
            brightness = values[Keys.brightness] ?: -1f,
            theme = values[Keys.theme].asEnumOr(ReaderTheme.DAY),
            fontSizeSp = (values[Keys.fontSize] ?: 18f).coerceIn(14f, 32f),
            fontFamily = values[Keys.font].asEnumOr(ReaderFont.SYSTEM),
            lineSpacing = values[Keys.lineSpacing].asEnumOr(SpacingLevel.STANDARD),
            paragraphSpacing = values[Keys.paragraphSpacing].asEnumOr(SpacingLevel.STANDARD),
            margin = values[Keys.margin].asEnumOr(SpacingLevel.STANDARD),
            pageMode = values[Keys.pageMode].asEnumOr(PageMode.SCROLL),
            autoDetectEncoding = values[Keys.detectEncoding] ?: true,
            autoDetectChapters = values[Keys.detectChapters] ?: true,
            autoGenerateCover = values[Keys.generateCover] ?: true,
        )
    }

    suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        context.readerDataStore.edit { values ->
            val current = ReaderPreferences(
                comicPageMode = values[Keys.comicMode] ?: "continuous",
            imageFit = values[Keys.imageFit] ?: "screen",
            pdfPageMode = values[Keys.pdfMode] ?: "continuous",
                pdfFit = values[Keys.pdfFit] ?: "page-width",
                pdfInvert = values[Keys.pdfInvert] ?: false,
                customColors = values[Keys.customColors] ?: false,
            backgroundHex = values[Keys.backgroundHex] ?: "F7F7F5",
            textHex = values[Keys.textHex] ?: "222222",
            indentParagraphs = values[Keys.indent] ?: true,
            autoNextChapter = values[Keys.autoNext] ?: true,
            keepScreenOn = values[Keys.keepOn] ?: false,
            brightness = values[Keys.brightness] ?: -1f,
            theme = values[Keys.theme].asEnumOr(ReaderTheme.DAY),
                fontSizeSp = values[Keys.fontSize] ?: 18f,
                fontFamily = values[Keys.font].asEnumOr(ReaderFont.SYSTEM),
                lineSpacing = values[Keys.lineSpacing].asEnumOr(SpacingLevel.STANDARD),
                paragraphSpacing = values[Keys.paragraphSpacing].asEnumOr(SpacingLevel.STANDARD),
                margin = values[Keys.margin].asEnumOr(SpacingLevel.STANDARD),
                pageMode = values[Keys.pageMode].asEnumOr(PageMode.SCROLL),
                autoDetectEncoding = values[Keys.detectEncoding] ?: true,
                autoDetectChapters = values[Keys.detectChapters] ?: true,
                autoGenerateCover = values[Keys.generateCover] ?: true,
            )
            val updated = transform(current)
            values[Keys.comicMode] = updated.comicPageMode
            values[Keys.imageFit] = updated.imageFit
            values[Keys.pdfMode] = updated.pdfPageMode
            values[Keys.pdfFit] = updated.pdfFit
            values[Keys.pdfInvert] = updated.pdfInvert
            values[Keys.customColors] = updated.customColors
            values[Keys.backgroundHex] = updated.backgroundHex
            values[Keys.textHex] = updated.textHex
            values[Keys.indent] = updated.indentParagraphs
            values[Keys.autoNext] = updated.autoNextChapter
            values[Keys.keepOn] = updated.keepScreenOn
            values[Keys.brightness] = updated.brightness
            values[Keys.theme] = updated.theme.name
            values[Keys.fontSize] = updated.fontSizeSp.coerceIn(14f, 32f)
            values[Keys.font] = updated.fontFamily.name
            values[Keys.lineSpacing] = updated.lineSpacing.name
            values[Keys.paragraphSpacing] = updated.paragraphSpacing.name
            values[Keys.margin] = updated.margin.name
            values[Keys.pageMode] = updated.pageMode.name
            values[Keys.detectEncoding] = updated.autoDetectEncoding
            values[Keys.detectChapters] = updated.autoDetectChapters
            values[Keys.generateCover] = updated.autoGenerateCover
        }
    }
}

private inline fun <reified T : Enum<T>> String?.asEnumOr(fallback: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback
