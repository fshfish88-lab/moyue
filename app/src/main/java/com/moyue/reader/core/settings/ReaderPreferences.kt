package com.moyue.reader.core.settings

enum class ReaderTheme { DAY, SEPIA, GREEN, GRAY, NIGHT }
enum class ReaderFont { SYSTEM, SERIF, SANS, LXGW }
enum class SpacingLevel { COMPACT, STANDARD, WIDE }
enum class PageMode { SCROLL, PAGED }

data class ReaderPreferences(
    val theme: ReaderTheme = ReaderTheme.DAY,
    val fontSizeSp: Float = 18f,
    val fontFamily: ReaderFont = ReaderFont.SYSTEM,
    val lineSpacing: SpacingLevel = SpacingLevel.STANDARD,
    val paragraphSpacing: SpacingLevel = SpacingLevel.STANDARD,
    val margin: SpacingLevel = SpacingLevel.STANDARD,
    val pageMode: PageMode = PageMode.SCROLL,
    val autoDetectEncoding: Boolean = true,
    val autoDetectChapters: Boolean = true,
    val autoGenerateCover: Boolean = true,
)
