package com.moyue.reader.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.moyue.reader.core.settings.ReaderTheme

private val AppScheme = lightColorScheme(
    primary = MoyueColors.Accent,
    onPrimary = Color.White,
    primaryContainer = MoyueColors.AccentSoft,
    background = MoyueColors.AppBackground,
    surface = MoyueColors.Card,
    onBackground = MoyueColors.Ink,
    onSurface = MoyueColors.Ink,
    onSurfaceVariant = MoyueColors.MutedInk,
    outlineVariant = MoyueColors.Divider,
    error = MoyueColors.Danger,
)

private fun readerScheme(theme: ReaderTheme) = when (theme) {
    ReaderTheme.DAY -> AppScheme.copy(background = Color(0xFFF7F7F5), surface = Color(0xFFF7F7F5))
    ReaderTheme.SEPIA -> AppScheme.copy(background = Color(0xFFF8F1E3), surface = Color(0xFFF8F1E3))
    ReaderTheme.GREEN -> AppScheme.copy(background = Color(0xFFE7EFE5), surface = Color(0xFFE7EFE5))
    ReaderTheme.GRAY -> AppScheme.copy(background = Color(0xFFE9E9E7), surface = Color(0xFFE9E9E7))
    ReaderTheme.NIGHT -> darkColorScheme(
        primary = Color(0xFFAEBFA8),
        background = Color(0xFF252525),
        surface = Color(0xFF252525),
        onBackground = Color(0xFFE8E5DD),
        onSurface = Color(0xFFE8E5DD),
        onSurfaceVariant = Color(0xFFBEBBB4),
        outlineVariant = Color(0xFF444440),
    )
}

@Composable
fun MoyueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppScheme, typography = Typography(), content = content)
}

@Composable
fun MoyueReaderTheme(theme: ReaderTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = readerScheme(theme), typography = Typography(), content = content)
}
