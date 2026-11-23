package com.moyue.reader.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.moyue.reader.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Shelf navigation tones within the shared paper design. */
@Composable
fun PaperShelfTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF242725), onPrimary = Color(0xFFFCFAF6),
        secondary = Color(0xFF5C6B58), secondaryContainer = Color(0xFFE9E5DE),
        background = Color(0xFFF5F2ED), onBackground = Color(0xFF242725),
        surface = Color(0xFFFCFAF6), onSurface = Color(0xFF242725),
        surfaceVariant = Color(0xFFE9E5DE), onSurfaceVariant = Color(0xFF716F69),
        outline = Color(0xFFDEDAD2), outlineVariant = Color(0xFFDEDAD2),
    )
    SystemBarsColor(scheme.background, darkIcons = true)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}

/** Shared native controls for the 2026-10-22 Figma paper design. */
object PaperIcons {
    val File: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_file)
    val More: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_more)
    val Trash: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_trash)
    val Image: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_image)
    val Download: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_download)
    val Share: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_share)
    val Back: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_back)
    val Bookmark: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_bookmark)
    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_check)
    val Warning: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_warning)
    val Tag: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_tag)
    val Globe: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_globe)
    val Chevron: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_chevron)
    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_close)
    val Folder: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_folder)
    val Heart: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_heart)
    val Menu: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_menu)
    val Sort: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_sort)
    val Grid: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_grid)
    val Sun: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_sun)
    val Edit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_edit)
    val Refresh: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_refresh)

    val Book: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_book)
    val Notes: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_notes)
    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_settings)
    val Search: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_search)
    val Filter: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_filter)
    val Add: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.paper_add)
}

@Composable
fun PaperNavigation(selected: Int, onShelf: () -> Unit, onNotes: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp)) {
                listOf(Triple("书架", PaperIcons.Book, onShelf), Triple("摘录", PaperIcons.Notes, onNotes),
                    Triple("设置", PaperIcons.Settings, onSettings)).forEachIndexed { index, (label, icon, action) ->
                    Column(Modifier.weight(1f).selectable(selected == index, onClick = action, role = Role.Tab)
                        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.background(if (selected == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            Icon(icon, null, Modifier.size(22.dp), tint = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PaperChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.widthIn(min = 58.dp).heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        val inactive = if (MaterialTheme.colorScheme.surfaceVariant == MaterialTheme.colorScheme.background)
            MaterialTheme.colorScheme.onSurface.copy(alpha = .06f) else MaterialTheme.colorScheme.surfaceVariant
        Surface(modifier = Modifier.widthIn(min = 58.dp), shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primary else inactive,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium) { Box(Modifier.padding(horizontal = 15.dp, vertical = 7.dp)) { label() } }
        }
    }
}

/** Exact native Figma paths, with the same rounded crop as the original frame. */
@Composable
fun PaperLandscape(modifier: Modifier = Modifier) {
    Box(modifier) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.paper_landscape),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
        )
        // Figma: center=(75% width, 23% height), radius=15% height. Keep the sun
        // circular when the mountain paths expand to a tablet or a narrow window.
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color(0xFFF4E9D8), size.height * .15f,
                androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .23f))
        }
    }
}
