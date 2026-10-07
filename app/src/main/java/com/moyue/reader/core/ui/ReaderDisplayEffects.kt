package com.moyue.reader.core.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import com.moyue.reader.core.settings.ReaderPreferences

/** Book and Markdown readers share screen brightness and keep-awake behavior. */
@Composable
fun ReaderDisplayEffects(preferences: ReaderPreferences) {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    DisposableEffect(window, view) {
        val oldKeep = view.keepScreenOn
        val oldBrightness = window?.attributes?.screenBrightness
        onDispose {
            view.keepScreenOn = oldKeep
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = oldBrightness ?: -1f } }
        }
    }
    SideEffect {
        view.keepScreenOn = preferences.keepScreenOn
        window?.let { it.attributes = it.attributes.apply { screenBrightness = preferences.brightness } }
    }
}
