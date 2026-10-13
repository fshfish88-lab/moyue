package com.moyue.reader.feature.reader

import com.moyue.reader.core.ui.MoyueMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.reader.core.model.ContentBlock
import com.moyue.reader.core.model.ReaderPosition
import com.moyue.reader.core.settings.PageMode
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.SpacingLevel
import com.moyue.reader.core.ui.ReaderDisplayEffects
import com.moyue.reader.core.ui.MoyueReaderTheme
import com.moyue.reader.core.ui.MoyueSpacing

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
    onScrollPosition: (Long, ReaderPosition, Float) -> Unit,
    onPrefetch: () -> Unit,
    onRefreshCatalog:()->Unit={},
    onCatalogSource:(()->Unit)?=null,
    onCatalogDiagnostic:(()->Unit)?=null,
    annotations: List<com.moyue.reader.core.database.AnnotationEntity> = emptyList(),
    onSelection: (com.moyue.reader.core.model.ReaderChapter, ReaderPosition, ReaderPosition, String) -> Unit = { _, _, _, _ -> },
    onAnnotations: () -> Unit = {},
    onBookmark: () -> Unit = {},
) {
    val pageCache = remember { ReaderPageCache() }
    var showChapters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val preferences = state.preferences
    // Toolbar visibility and catalog state do not affect text shaping or scroll contents.
    val content = remember(state.chapter, state.position, preferences, state.canGoPrevious,
        state.canGoNext, state.error, state.scrollWindow, state.prefetching) {
        ReaderContentState(state.chapter, state.position, preferences, state.canGoPrevious,
            state.canGoNext, state.error, state.scrollWindow, state.prefetching)
    }
    val contentAnnotations = remember(annotations) { indexReaderAnnotations(annotations) }
    val latestSelection by rememberUpdatedState(onSelection)
    val contentSelection = remember {
        { chapter: com.moyue.reader.core.model.ReaderChapter, start: ReaderPosition, end: ReaderPosition, type: String ->
            latestSelection(chapter, start, end, type)
        }
    }
    val toggleControls by rememberUpdatedState(onToggleControls)
    ReaderDisplayEffects(preferences)
    MoyueReaderTheme(preferences) {
        Box(
            Modifier
                .fillMaxSize().background(MaterialTheme.colorScheme.background)
                .then(if(preferences.pageMode==PageMode.SCROLL) Modifier.pointerInput(Unit) {
                    detectTapGestures { toggleControls() }
                } else Modifier),
        ) {
            key(state.scrollSession, if (preferences.pageMode == PageMode.SCROLL) 0L else state.chapter.id, preferences.pageMode) {
            if (preferences.pageMode == PageMode.SCROLL) {
                ScrollingChapter(content, onScrollPosition, onPrefetch, onNext, onToggleControls, contentAnnotations, contentSelection)
            } else {
                PagedChapter(content, pageCache, onPosition, onPrevious, onNext, onToggleControls, contentAnnotations, contentSelection)
            }

            }

            ReaderToolbar(
                visible = state.controlsVisible,
                edge = -1,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Text(
                            state.chapter.title.ifBlank { state.bookTitle },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                        }
                    },
                    actions = {
                        TextButton(onBookmark) { Text("书签") }
                        TextButton(onAnnotations) { Text("标注") }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多阅读设置")
                        }
                    },
                )
            }

            ReaderToolbar(
                visible = state.controlsVisible,
                edge = 1,
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
                catalogMessage=state.catalogMessage,
                refreshing=state.catalogRefreshing,
                onRefresh=onRefreshCatalog.takeIf {state.catalogMessage!=null},
                onSource=onCatalogSource.takeIf {state.catalogMessage!=null},
                onDiagnostic=onCatalogDiagnostic.takeIf {state.catalogMessage!=null},
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

/** Keep the bars composed across taps; animate placement layers, not creation of every button.
 * Hidden bars are not placed, so they cannot intercept reading gestures or accessibility focus. */
@Composable
private fun ReaderToolbar(visible: Boolean, edge: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val opacity = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) MoyueMotion.Standard else MoyueMotion.Fast, easing = MoyueMotion.Easing),
        label = "readerToolbar",
    )
    Box(modifier
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
        .layout { measurable, constraints ->
            val bar = measurable.measure(constraints)
            layout(bar.width, bar.height) {
                if (opacity.value > 0f) bar.placeWithLayer(0, 0) {
                    alpha = opacity.value
                    translationY = edge * (bar.height / 8).coerceAtMost(48) * (1f - opacity.value)
                }
            }
        },
    ) { content() }
}

private data class ScrollItem(val chapter: com.moyue.reader.core.model.ReaderChapter, val block: Int) {
    val key: String get() = "${chapter.id}:$block"
}

private data class ReaderContentState(
    val chapter: com.moyue.reader.core.model.ReaderChapter,
    val position: ReaderPosition,
    val preferences: ReaderPreferences,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
    val error: String?,
    val scrollWindow: List<com.moyue.reader.core.model.ReaderChapter>,
    val prefetching: Boolean,
)

@Composable
private fun ScrollingChapter(state: ReaderContentState, onPosition: (Long, ReaderPosition, Float) -> Unit, onPrefetch: () -> Unit, onNext: () -> Unit, onToggleControls: () -> Unit,
    annotations: Map<Long, List<IndexedReaderAnnotation>>, onSelection: (com.moyue.reader.core.model.ReaderChapter, ReaderPosition, ReaderPosition, String) -> Unit) {
    val window = state.scrollWindow.ifEmpty { listOf(state.chapter) }
    val highlights = remember(window, annotations) { window.associate { it.id to chapterHighlights(it, annotations[it.id].orEmpty()) } }
    val entries = remember(window) { window.flatMap { chapter -> listOf(ScrollItem(chapter, -1)) + chapter.blocks.indices.map { ScrollItem(chapter, it) } } }
    val initial = remember {
        val block = if (state.position.blockIndex == 0 && state.position.charOffset == 0) -1 else state.position.blockIndex
        entries.indexOfFirst { it.chapter.id == state.chapter.id && it.block == block }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initial)
    val layouts = remember { mutableStateMapOf<String, android.text.Layout>() }
    var restored by remember { mutableStateOf(state.position.charOffset == 0) }
    LaunchedEffect(Unit) {
        if (!restored) {
            val item = entries.getOrNull(initial)
            if (item != null) {
                val layout = snapshotFlow { layouts[item.key] }.filterNotNull().first()
                val line = layout.getLineForOffset(state.position.charOffset.coerceAtMost(layout.text.length))
                listState.scrollToItem(initial, layout.getLineTop(line).toInt())
            }
            restored = true
        }
    }
    val itemsByKey = remember(entries) { entries.associateBy { it.key } }
    val indexesByKey = remember(entries) { entries.mapIndexed { index, item -> item.key to index }.toMap() }
    val latestPosition by rememberUpdatedState(onPosition)
    val latestPrefetch by rememberUpdatedState(onPrefetch)
    LaunchedEffect(entries, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
            val entry = itemsByKey[first?.key] ?: return@snapshotFlow null
            val pixels = listState.firstVisibleItemScrollOffset
            val layout = layouts[entry.key]
            val offset = layout?.getLineStart(layout.getLineForVertical(pixels)) ?: 0
            Triple(entry.chapter.id, ReaderPosition(entry.block.coerceAtLeast(0), offset),
                (entry.block.coerceAtLeast(0).toFloat() / entry.chapter.blocks.size.coerceAtLeast(1)).coerceIn(0f, 1f))
        }.filterNotNull().distinctUntilChanged().collect { (chapter, position, progress) ->
            latestPosition(chapter, position, progress)
        }
    }
    LaunchedEffect(entries, state.chapter.id, state.preferences.autoNextChapter, state.error) {
        if (!state.preferences.autoNextChapter || state.error != null) return@LaunchedEffect
        snapshotFlow {
            val last = indexesByKey[listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key] ?: -1
            last >= 0 && last >= entries.lastIndex - 5
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) latestPrefetch() }
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    if (rememberReaderTypeface(state.preferences.fontFamily) == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = state.preferences.margin.dpValue + safe.calculateLeftPadding(direction),
            end = state.preferences.margin.dpValue + safe.calculateRightPadding(direction),
            top = safe.calculateTopPadding() + TopBarReserve,
            bottom = safe.calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(state.preferences.paragraphSpacing.dpValue)) {
        items(entries.size, key = { entries[it].key }) { index ->
            val item = entries[index]
            if (item.block < 0) {
                // Chapter openings get air above and a short rule, so a new chapter reads as a
                // break in the text rather than as one more paragraph.
                Column(Modifier.padding(top = 26.dp, bottom = 10.dp)) {
                    Box(
                        Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .22f)),
                    )
                    Text(
                        item.chapter.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = MoyueSpacing.Section),
                    )
                }
            } else {
                val block = item.chapter.blocks[item.block]
                if (block is ContentBlock.Image) ReaderImage(block)
                else {
                    val text = com.moyue.reader.feature.annotations.blockText(block)
                    AnnotatedReaderText(text, item.chapter, listOf(com.moyue.reader.feature.annotations.TextSegment(0, text.length, item.block, 0)), state.preferences,
                        highlights[item.chapter.id].orEmpty(), Modifier.fillMaxWidth(), indent = state.preferences.indentParagraphs && block is ContentBlock.Text,
                        onTap = { onToggleControls() },
                        onLayout = { layouts[item.key] = it }, onSelection = onSelection)
                }
            }
        }
        item {
            if (state.prefetching) CircularProgressIndicator(Modifier.padding(16.dp))
            else if (state.error != null) TextButton(onClick = onPrefetch) { Text("加载下一章失败，点击重试") }
            else if (!state.preferences.autoNextChapter && state.canGoNext) TextButton(onClick = onNext) { Text("下一章") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@android.annotation.SuppressLint("InlinedApi")
private fun PagedChapter(state: ReaderContentState, cache: ReaderPageCache, onPosition: (ReaderPosition, Float) -> Unit, onPrevious:()->Unit,onNext: () -> Unit,onToggleControls:()->Unit,
    annotations: Map<Long, List<IndexedReaderAnnotation>>, onSelection: (com.moyue.reader.core.model.ReaderChapter, ReaderPosition, ReaderPosition, String) -> Unit) {
    val highlights = remember(state.chapter, annotations) { chapterHighlights(state.chapter, annotations[state.chapter.id].orEmpty()) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    val margin = state.preferences.margin.dpValue
    // Explicit insets instead of a hardcoded 76dp: on a device with a tall status bar or a gesture
    // pill the old constant either clipped the first line or left a visible gap.
    val contentPadding = WindowInsets.safeDrawing.asPaddingValues()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(
                start = margin + contentPadding.calculateLeftPadding(LocalLayoutDirection.current),
                end = margin + contentPadding.calculateRightPadding(LocalLayoutDirection.current),
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        // Reserve the footer row out of the paged height so the last line never sits under it.
        val footerHeight = 40.dp
        val height = with(density) { (maxHeight - footerHeight).roundToPx() }.coerceAtLeast(1)
        val fontPx = with(density) { state.preferences.fontSizeSp.sp.toPx() }
        // Pagination depends only on the text-shaping fields. Keying the layout pass on the whole
        // preference object meant that toggling, say, "屏幕常亮" cancelled and re-ran pagination for
        // the entire chapter, blanking the page to a spinner.
        val layoutPreferences = remember(
            state.preferences.fontSizeSp,
            state.preferences.fontFamily,
            state.preferences.lineSpacing,
            state.preferences.paragraphSpacing,
            state.preferences.indentParagraphs,
        ) {
            state.preferences.copy(
                theme = com.moyue.reader.core.settings.ReaderTheme.DAY,
                customColors = false,
                backgroundHex = "F7F7F5",
                textHex = "222222",
                brightness = -1f,
                keepScreenOn = false,
            )
        }
        val face = rememberReaderTypeface(state.preferences.fontFamily)
        val layout by produceState<Result<List<ReaderPage>>?>(null, state.chapter.blocks, layoutPreferences, width, height, fontPx, face) {
            value = null
            if (face == null) return@produceState
            value = try { Result.success(withContext(Dispatchers.Default) { cache.pages(state.chapter.blocks, layoutPreferences, width, height, fontPx, face) }) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        }
        val pages = layout?.getOrNull()
        if (pages == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (layout == null) CircularProgressIndicator() else Text(layout?.exceptionOrNull()?.message ?: "分页失败")
            }
            return@BoxWithConstraints
        }
        val initial = remember(pages) { pages.indexOfLast {
            it.start.blockIndex < state.position.blockIndex ||
                (it.start.blockIndex == state.position.blockIndex && it.start.charOffset <= state.position.charOffset)
        }.coerceAtLeast(0) }
        key(pages) {
            val pager = rememberPagerState(initialPage = initial, pageCount = { pages.size + if (state.canGoNext) 1 else 0 })
            LaunchedEffect(pager, pages) {
                snapshotFlow { pager.settledPage }.collect { index ->
                    if (index < pages.size) onPosition(pages[index].start, (index + 1f) / pages.size)
                    else if (state.preferences.autoNextChapter) onNext()
                }
            }
            var turnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val turnPage: (Int) -> Unit = turn@{ direction ->
                // A new pointer-down interrupts animated scrolling and may snap backwards. Tap turns
                // settle immediately; dragging keeps the pager's own swipe/fling behavior.
                if (selecting || pager.isScrollInProgress || turnJob?.isActive == true) return@turn
                val target = pager.settledPage + direction
                if (target in pages.indices) {
                    turnJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        pager.scrollToPage(target)
                    }
                } else if (direction < 0 && state.canGoPrevious) onPrevious()
                else if (direction > 0 && state.canGoNext) onNext()
            }
            val handleTap: (Float) -> Unit = { fraction ->
                if (!selecting) when {
                    fraction < .25f -> turnPage(-1)
                    fraction > .75f -> turnPage(1)
                    else -> onToggleControls()
                }
            }
            val hostView = LocalView.current
            val viewportLeft = remember { floatArrayOf(0f) }
            val nativeTap: (Float) -> Unit = { screenX ->
                val origin = IntArray(2)
                hostView.getLocationOnScreen(origin)
                handleTap((screenX - origin[0] - viewportLeft[0]) / width)
            }
            val latestTap by rememberUpdatedState(handleTap)
            HorizontalPager(
                state = pager,
                userScrollEnabled = !selecting,
                modifier = Modifier.fillMaxWidth().height((maxHeight - footerHeight).coerceAtLeast(1.dp))
                    .onGloballyPositioned { viewportLeft[0] = it.positionInRoot().x }
                    .pointerInput(pager,state.canGoPrevious,state.canGoNext) {
                        detectTapGestures {offset->
                            latestTap(offset.x / size.width.coerceAtLeast(1))
                        }
                    },
            ) { page ->
                if (page < pages.size) {
                    if (pages[page].image != null) {
                        ReaderImage(requireNotNull(pages[page].image), paged = true)
                    } else {
                    AnnotatedReaderText(pages[page].text.trimEnd('\n'), state.chapter, pages[page].segments, state.preferences,
                        highlights, Modifier.fillMaxSize(), onTap = nativeTap, onSelecting = { selecting = it }, onSelection = onSelection)
                    }
                }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onNext) { Text("下一章") }
                }
            }
            // Footer: page number centred and quiet, with icon-only turn controls. The old version
            // put three pieces of body text on one line, so "3 / 12" read like debug output.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(footerHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { turnPage(-1) },
                    enabled = pager.currentPage > 0 || state.canGoPrevious,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一页")
                }
                Text(
                    "${minOf(pager.currentPage + 1, pages.size)} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { turnPage(1) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一页")
                }
            }
        }
    }
}

/** Material3's default TopAppBar height. Reading text reserves this so a line never sits under the
 *  control bar when it fades in. */
private val TopBarReserve = 64.dp

@Composable
private fun ReaderControls(
    state: ReaderState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChapters: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            LinearProgressIndicator(
                progress = { state.chapterProgress },
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(3.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MoyueSpacing.Tight, vertical = MoyueSpacing.Micro),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onPrevious, enabled = state.canGoPrevious) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                    Text("上一章")
                }
                Text(
                    state.chapter.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                TextButton(onClick = onNext, enabled = state.canGoNext) {
                    Text("下一章")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            // Unfilled buttons: the filled green capsules carried more visual weight than the text
            // they sat under, which works against an immersive reading surface.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                ReaderMenuButton("目录", onChapters) { Icon(Icons.Default.Menu, null) }
                Box(Modifier.width(MoyueSpacing.Section))
                ReaderMenuButton("阅读设置", onSettings) { Icon(Icons.Default.Settings, null) }
            }
        }
    }
}

@Composable
private fun ReaderMenuButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = MoyueSpacing.Page, vertical = 10.dp)) {
        icon()
        Text(label, Modifier.padding(start = MoyueSpacing.Tight))
    }
}

private val SpacingLevel.dpValue get() = when (this) {
    SpacingLevel.COMPACT -> 12.dp
    SpacingLevel.STANDARD -> 20.dp
    SpacingLevel.WIDE -> 28.dp
}
