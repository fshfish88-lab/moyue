package com.moyue.reader.core.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object MoyueColors {
    val AppBackground = Color(0xFFF7F7F5)
    val ReaderBackground = Color(0xFFF8F7F4)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF252525)
    val MutedInk = Color(0xFF70716F)
    val Accent = Color(0xFF5C6B58)
    val AccentDeep = Color(0xFF3F4A3C)
    val AccentSoft = Color(0xFFE5EAE2)
    val Divider = Color(0xFFE7E7E2)
    val Danger = Color(0xFF9B3B38)
    val Scrim = Color(0x33000000)
}

/**
 * Reader themes exactly as defined by 墨阅_UI设计说明.md §5. Kept here (not in the settings
 * module) so the settings sheet, the reader surface and the system bars all read one source.
 */
enum class ReaderPalette(val label: String, val background: Color, val ink: Color) {
    DAY("日间", Color(0xFFF8F7F4), Color(0xFF252525)),
    SEPIA("米黄", Color(0xFFF4EEDC), Color(0xFF2B2925)),
    GREEN("淡绿", Color(0xFFE9F0E7), Color(0xFF293029)),
    GRAY("灰色", Color(0xFFE7E7E5), Color(0xFF292929)),
    NIGHT("夜间", Color(0xFF171717), Color(0xFFCFCFCF)),
}

object MoyueSpacing {
    /** Horizontal page gutter. Every top-level screen uses this so gutters line up across screens. */
    val Page = 20.dp
    val Section = 18.dp
    val Item = 12.dp
    val Tight = 8.dp
    val Micro = 4.dp
}

object MoyueRadius {
    val Cover = 6.dp
    val Small = 10.dp
    val Card = 12.dp
    val Hero = 16.dp
    val Sheet = 24.dp

    // RoundedCornerShape instances, built once.
    val coverShape = RoundedCornerShape(Cover)
    val smallShape = RoundedCornerShape(Small)
    val cardShape = RoundedCornerShape(Card)
    val heroShape = RoundedCornerShape(Hero)
    val sheetShape = RoundedCornerShape(topStart = Sheet, topEnd = Sheet)
}

object MoyueMotion {
    const val Fast = 150
    const val Standard = 200
    val Easing = FastOutSlowInEasing
    fun enter(edge: Int = 0): EnterTransition = fadeIn(tween(Standard, easing = Easing)) +
        slideInVertically(tween(Standard, easing = Easing)) { edge * (it / 8).coerceAtMost(48) }
    fun exit(edge: Int = 0): ExitTransition = fadeOut(tween(Fast, easing = Easing)) +
        slideOutVertically(tween(Fast, easing = Easing)) { edge * (it / 8).coerceAtMost(48) }
    fun expand(): EnterTransition = fadeIn(tween(Standard, easing = Easing)) + expandVertically(tween(Standard, easing = Easing))
    fun collapse(): ExitTransition = fadeOut(tween(Fast, easing = Easing)) + shrinkVertically(tween(Fast, easing = Easing))
}
