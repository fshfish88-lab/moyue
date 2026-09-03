package com.moyue.reader.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPreferencesTest {
    @Test
    fun defaultsMatchTheReadingSpecification() {
        val preferences = ReaderPreferences()

        assertEquals(ReaderTheme.DAY, preferences.theme)
        assertEquals(18f, preferences.fontSizeSp)
        assertEquals(PageMode.SCROLL, preferences.pageMode)
        assertEquals(SpacingLevel.STANDARD, preferences.lineSpacing)
    }
}
