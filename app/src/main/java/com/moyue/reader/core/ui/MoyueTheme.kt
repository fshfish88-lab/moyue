package com.moyue.reader.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderTheme

/**
 * Typography follows 墨阅_UI设计说明.md §6: five levels, no more.
 * The old code passed a bare `Typography()`, which left Material's default scale in place and is
 * why titles drifted in size between screens.
 */
private val MoyueTypography = Typography().run {
    copy(
        headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = .8.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    )
}

/** The wordmark: a serif lockup with open tracking, used on the shelf header only. */
val MoyueWordmark = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    letterSpacing = 6.sp,
)

/**
 * Every colour role Material3 can reach has to be assigned here.
 *
 * The previous build listed only ~15 roles, so components that read an unlisted one
 * (`surfaceContainerHighest` for bottom sheets, `surfaceVariant` for the reader control bar,
 * `surfaceTint` for elevation overlays) fell back to `lightColorScheme()`'s defaults — which are
 * the lavender #F3EDF7 family. That is the stray purple block in bug反馈/img_001.jpg.
 */
private val AppScheme = lightColorScheme(
    primary = MoyueColors.Accent,
    onPrimary = Color.White,
    primaryContainer = MoyueColors.AccentSoft,
    onPrimaryContainer = MoyueColors.AccentDeep,
    inversePrimary = MoyueColors.AccentSoft,
    secondary = MoyueColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = MoyueColors.AccentSoft,
    onSecondaryContainer = MoyueColors.AccentDeep,
    tertiary = MoyueColors.Accent,
    onTertiary = Color.White,
    tertiaryContainer = MoyueColors.AccentSoft,
    onTertiaryContainer = MoyueColors.AccentDeep,
    background = MoyueColors.AppBackground,
    onBackground = MoyueColors.Ink,
    surface = MoyueColors.Card,
    onSurface = MoyueColors.Ink,
    surfaceVariant = MoyueColors.AppBackground,
    onSurfaceVariant = MoyueColors.MutedInk,
    surfaceTint = Color.Transparent,
    surfaceBright = MoyueColors.Card,
    surfaceDim = MoyueColors.AppBackground,
    surfaceContainerLowest = MoyueColors.Card,
    surfaceContainerLow = MoyueColors.AppBackground,
    surfaceContainer = MoyueColors.AppBackground,
    surfaceContainerHigh = MoyueColors.AppBackground,
    surfaceContainerHighest = MoyueColors.AppBackground,
    inverseSurface = MoyueColors.Ink,
    inverseOnSurface = MoyueColors.AppBackground,
    error = MoyueColors.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFF7E4E2),
    onErrorContainer = MoyueColors.Danger,
    outline = MoyueColors.Divider,
    outlineVariant = MoyueColors.Divider,
    scrim = MoyueColors.Scrim,
)

/** Reader themes only vary background/ink; everything else stays on the app scheme. */
private fun readerScheme(theme: ReaderTheme): ColorScheme {
    val palette = theme.palette
    val night = theme == ReaderTheme.NIGHT
    val base = if (night) darkColorScheme(
        primary = Color(0xFFAEBFA8),
        onPrimary = Color(0xFF1B231A),
        primaryContainer = Color(0xFF33402F),
        onPrimaryContainer = Color(0xFFD6E2D1),
        secondary = Color(0xFFAEBFA8),
        secondaryContainer = Color(0xFF33402F),
        onSecondaryContainer = Color(0xFFD6E2D1),
        tertiary = Color(0xFFAEBFA8),
        error = Color(0xFFE5A9A6),
        onError = Color(0xFF44201F),
        outline = Color(0xFF444440),
        outlineVariant = Color(0xFF444440),
        scrim = Color(0xCC000000),
    ) else AppScheme

    return base.copy(
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.background,
        onSurface = palette.ink,
        surfaceVariant = palette.background,
        onSurfaceVariant = palette.ink.copy(alpha = .72f),
        surfaceTint = Color.Transparent,
        surfaceBright = palette.background,
        surfaceDim = palette.background,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.background,
        surfaceContainer = palette.background,
        surfaceContainerHigh = palette.background,
        surfaceContainerHighest = palette.background,
        inverseSurface = palette.ink,
        inverseOnSurface = palette.background,
        outline = palette.ink.copy(alpha = .18f),
        outlineVariant = palette.ink.copy(alpha = .18f),
    )
}

val ReaderTheme.palette: ReaderPalette get() = ReaderPalette.entries[ordinal]

/**
 * Paints the system bars to match the current surface and stops the platform from drawing its own
 * translucent scrim over the navigation bar (Android 10+), which is what let the default lavender
 * tint survive on top of the reader background.
 *
 * The values in place before this composable entered are captured once and written back on dispose,
 * so leaving the reader restores the shelf's bar colours instead of stranding the reading palette.
 */
@Composable
fun SystemBarsColor(background: Color, darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? android.app.Activity)?.window ?: return
    val argb = background.toArgb()

    DisposableEffect(window) {
        val previousStatus = window.statusBarColor
        val previousNav = window.navigationBarColor
        val previousContrast = if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced
        } else null
        onDispose {
            window.statusBarColor = previousStatus
            window.navigationBarColor = previousNav
            if (android.os.Build.VERSION.SDK_INT >= 29 && previousContrast != null) window.isNavigationBarContrastEnforced = previousContrast
        }
    }

    SideEffect {
        window.statusBarColor = argb
        window.navigationBarColor = argb
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Android 10+ scrims the nav bar translucently unless this is cleared.
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }
}

@Composable
fun MoyueTheme(content: @Composable () -> Unit) {
    SystemBarsColor(background = MoyueColors.AppBackground, darkIcons = true)
    MaterialTheme(colorScheme = AppScheme, typography = MoyueTypography, content = content)
}

@Composable
fun MoyueReaderTheme(preferences: ReaderPreferences, content: @Composable () -> Unit) {
    val original = readerScheme(preferences.theme)
    val bg = if (preferences.customColors) {
        runCatching { Color(("#" + preferences.backgroundHex).toColorInt()) }.getOrDefault(original.background)
    } else original.background
    val ink = if (preferences.customColors) {
        runCatching { Color(("#" + preferences.textHex).toColorInt()) }.getOrDefault(original.onBackground)
    } else original.onBackground

    val scheme = original.copy(
        background = bg,
        onBackground = ink,
        surface = bg,
        onSurface = ink,
        surfaceVariant = bg,
        onSurfaceVariant = ink.copy(alpha = .72f),
        surfaceTint = Color.Transparent,
        // The night scheme comes from darkColorScheme(), which never assigns these, so without an
        // explicit copy they stay dark even when the user paints a light custom background.
        surfaceBright = bg,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = bg,
        surfaceContainer = bg,
        surfaceContainerHigh = bg,
        surfaceContainerHighest = bg,
        inverseSurface = ink,
        inverseOnSurface = bg,
        secondaryContainer = bg,
        outline = ink.copy(alpha = .18f),
        outlineVariant = ink.copy(alpha = .18f),
    )
    SystemBarsColor(background = bg, darkIcons = appWantsDarkIcons(ink))
    MaterialTheme(colorScheme = scheme, typography = MoyueTypography, content = content)
}

/**
 * "Dark icons" means the bar content (clock, gesture pill) is drawn dark, which is what we want on a
 * light surface. So this is true when the reading ink colour is dark.
 */
private fun appWantsDarkIcons(ink: Color): Boolean = ink.luminance() < .5f
