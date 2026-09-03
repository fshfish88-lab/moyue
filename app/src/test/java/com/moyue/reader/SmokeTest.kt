package com.moyue.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun packageNameIsStable() {
        assertEquals("com.moyue.reader", BuildConfig.APPLICATION_ID)
    }
}
