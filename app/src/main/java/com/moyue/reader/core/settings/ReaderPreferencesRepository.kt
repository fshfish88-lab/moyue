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
