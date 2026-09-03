package com.moyue.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.core.settings.ReaderFont
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.SpacingLevel
import com.moyue.reader.core.ui.MoyueReaderTheme

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderState,
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChapter: (Int) -> Unit,
    onPosition: (ReaderPosition, Float) -> Unit,
    onPreferences: (ReaderPreferences) -> Unit,
) {
    var showChapters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val preferences = state.preferences
    MoyueReaderTheme(preferences.theme) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state.controlsVisible) {
                    detectTapGestures { offset ->
                        if (offset.x in size.width * .25f..size.width * .75f) onToggleControls()
                    }
                },
        ) {
            if (preferences.pageMode == PageMode.SCROLL) {
                ScrollingChapter(state, onPosition)
            } else {
                PagedChapter(state, onPosition)
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    title = { Text(state.bookTitle, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多阅读设置")
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderControls(
                    state = state,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onChapters = { showChapters = true },
                    onSettings = { showSettings = true },
                )
            }
        }
        if (showChapters) {
            ChapterSheet(
                state.chapters,
                state.chapter.index,
                onSelect = { showChapters = false; onChapter(it) },
                onDismiss = { showChapters = false },
            )
        }
        if (showSettings) {
            ReaderSettingsSheet(
                preferences,
                onChange = onPreferences,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ScrollingChapter(
    state: ReaderState,
    onPosition: (ReaderPosition, Float) -> Unit,
) {
    val listState = rememberLazyListState(state.position.blockIndex)
    LaunchedEffect(state.chapter.id, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.totalItemsCount }
            .collect { (index, count) ->
                val progress = if (count <= 1) 0f else index.toFloat() / (count - 1)
                onPosition(ReaderPosition(index.coerceAtLeast(0), 0), progress)
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = state.preferences.margin.dpValue,
            vertical = 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(state.preferences.paragraphSpacing.dpValue),
    ) {
        item {
            Text(
                state.chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        items(state.chapter.blocks.size, key = { it }) { index ->
            ReaderBlock(state.chapter.blocks[index], state.preferences)
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedChapter(
    state: ReaderState,
    onPosition: (ReaderPosition, Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val capacity = remember(maxWidth, maxHeight, state.preferences) {
            val columns = (maxWidth.value / (state.preferences.fontSizeSp * 1.05f)).toInt().coerceAtLeast(8)
            val rows = (maxHeight.value / (state.preferences.fontSizeSp * state.preferences.lineHeightFactor)).toInt().coerceAtLeast(8)
            (columns * rows).coerceAtLeast(80)
        }
        val pages = remember(state.chapter.id, capacity) {
            ReaderPaginator().paginate(state.chapter.blocks, capacity)
        }
        val initial = pages.indexOfLast {
            it.start.blockIndex < state.position.blockIndex ||
                (it.start.blockIndex == state.position.blockIndex && it.start.charOffset <= state.position.charOffset)
        }.coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = initial, pageCount = pages::size)
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }.collect { index ->
                onPosition(pages[index].start, (index + 1f) / pages.size)
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            Text(
                pages[page].text,
                fontSize = state.preferences.fontSizeSp.sp,
                lineHeight = (state.preferences.fontSizeSp * state.preferences.lineHeightFactor).sp,
                fontFamily = state.preferences.font,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = state.preferences.margin.dpValue, vertical = 76.dp),
            )
        }
        Text(
            "${pagerState.currentPage + 1} / ${pages.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun ReaderBlock(block: ContentBlock, preferences: ReaderPreferences) {
    val text = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Heading -> block.text
        is ContentBlock.Quote -> block.text
        is ContentBlock.Image -> block.description ?: "图片"
        ContentBlock.Divider -> "· · ·"
    }
    Text(
        text,
        fontSize = preferences.fontSizeSp.sp,
        lineHeight = (preferences.fontSizeSp * preferences.lineHeightFactor).sp,
        fontFamily = preferences.font,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun ReaderControls(
    state: ReaderState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChapters: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            LinearProgressIndicator(
                progress = { state.chapterProgress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onPrevious, enabled = state.canGoPrevious) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                    Text("上一章")
                }
                Text(state.chapter.title, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onNext, enabled = state.canGoNext) {
                    Text("下一章")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ReaderMenuButton("目录", onChapters) { Icon(Icons.Default.Menu, null) }
                    ReaderMenuButton("阅读设置", onSettings) { Icon(Icons.Default.Settings, null) }
                }
            }
        }
    }
}

@Composable
private fun ReaderMenuButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
        icon()
        Text(label, Modifier.padding(start = 8.dp))
    }
}

private val ReaderPreferences.font: FontFamily get() = when (fontFamily) {
    ReaderFont.SYSTEM, ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF, ReaderFont.LXGW -> FontFamily.Serif
}

private val ReaderPreferences.lineHeightFactor: Float get() = when (lineSpacing) {
    SpacingLevel.COMPACT -> 1.45f
    SpacingLevel.STANDARD -> 1.7f
    SpacingLevel.WIDE -> 2.0f
}

private val SpacingLevel.dpValue get() = when (this) {
    SpacingLevel.COMPACT -> 12.dp
    SpacingLevel.STANDARD -> 20.dp
    SpacingLevel.WIDE -> 28.dp
}
