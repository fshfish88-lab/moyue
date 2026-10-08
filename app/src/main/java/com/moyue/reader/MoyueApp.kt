package com.moyue.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.moyue.reader.core.database.BookEntity
import com.moyue.reader.core.database.ChapterEntity
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.ui.MoyueMotion
import com.moyue.reader.core.ui.MoyueTheme
import com.moyue.reader.feature.bookdetail.BookDetailScreen
import com.moyue.reader.feature.bookshelf.BookshelfScreen
import com.moyue.reader.feature.importbook.ImportProgressDialog
import com.moyue.reader.feature.importbook.ImportSheet
import com.moyue.reader.feature.importbook.ImportState
import com.moyue.reader.feature.importbook.SafeWebViewScreen
import com.moyue.reader.feature.importbook.WebImportPreview
import com.moyue.reader.feature.reader.ReaderRepository
import com.moyue.reader.feature.reader.ReaderScreen
import com.moyue.reader.feature.reader.ReaderViewModel
import com.moyue.reader.feature.reader.RoomReaderDataSource
import com.moyue.reader.feature.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface AppScreen {
    data object Shelf : AppScreen
    data object Settings : AppScreen
    data object Annotations : AppScreen
    data object Search : AppScreen
    data class Reader(val bookId: Long, val chapterIndex: Int? = null, val anchor: String? = null) : AppScreen
    data class Markdown(val bookId: Long, val edit: Boolean = false, val anchor: String? = null) : AppScreen
    data class Pdf(val bookId: Long, val anchor: String? = null) : AppScreen
    data class Visual(val bookId: Long, val anchor: String? = null) : AppScreen
    data class Detail(val bookId: Long) : AppScreen
    data class Browser(val url: String) : AppScreen
}

@Composable
fun MoyueApp(container: MoyueContainer, sharedUrl: String? = null) {
    val books by container.database.bookDao().observeAll().collectAsState(initial = emptyList())
    val preferences by container.preferences.preferences.collectAsState(initial = ReaderPreferences())
    val updateState by container.updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var screen: AppScreen by remember { mutableStateOf(AppScreen.Shelf) }
    var showImport by remember { mutableStateOf(!sharedUrl.isNullOrBlank()) }
    var importState by remember { mutableStateOf<ImportState?>(null) }
    var webPreview by remember { mutableStateOf<WebImportPreview?>(null) }
    var webBusy by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    var pendingWebUrl by remember { mutableStateOf(sharedUrl.orEmpty()) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showImport = false
            scope.launch {
                importState = container.importService.importDocument(
                    uri,
                    displayName(container.applicationContext, uri),
                    onProgress = { importState = it },
                )
            }
        }
    }

    fun openContent(id: Long, edit: Boolean = false, anchor: String? = null) {
        scope.launch {
            val book = container.database.bookDao().get(id) ?: return@launch
            screen = when (com.moyue.reader.core.document.ReaderEngineRegistry.item(book).format) {
                com.moyue.reader.core.document.DocumentFormat.MARKDOWN -> AppScreen.Markdown(id,edit,anchor)
                com.moyue.reader.core.document.DocumentFormat.PDF -> AppScreen.Pdf(id,anchor)
                com.moyue.reader.core.document.DocumentFormat.IMAGE, com.moyue.reader.core.document.DocumentFormat.COMIC -> AppScreen.Visual(id,anchor)
                else -> AppScreen.Reader(id,anchor=anchor)
            }
        }
    }

    MoyueTheme {
        BackHandler(enabled = screen != AppScreen.Shelf && screen !is AppScreen.Reader && screen !is AppScreen.Markdown && screen !is AppScreen.Pdf && screen !is AppScreen.Visual) { screen = AppScreen.Shelf }
        AnimatedContent(targetState = screen, label = "screen", transitionSpec = {
            (slideInHorizontally(tween(MoyueMotion.Standard, easing = MoyueMotion.Easing)) { if (targetState == AppScreen.Shelf) -it / 12 else it / 12 } + fadeIn(tween(MoyueMotion.Standard))) togetherWith
                (slideOutHorizontally(tween(MoyueMotion.Fast, easing = MoyueMotion.Easing)) { if (targetState == AppScreen.Shelf) it / 12 else -it / 12 } + fadeOut(tween(MoyueMotion.Fast)))
        }) { destination ->
        when (destination) {
            AppScreen.Shelf -> BookshelfScreen(
                books = books,
                onOpenBook = { openContent(it) },
                onEditMarkdown = { openContent(it, true) },
                onBookDetails = { screen = AppScreen.Detail(it) },
                onAddBook = { showImport = true },
                onSettings = { screen = AppScreen.Settings },
                onGlobalSearch = { screen = AppScreen.Search },
                onAnnotations = { screen = AppScreen.Annotations },
                onDeleteBook = { id ->
                    scope.launch {
                        runCatching { deleteShelfBook(container, id) }
                            .onSuccess { snackbar.showSnackbar("书籍已删除") }
                            .onFailure { snackbar.showSnackbar("删除失败，请重试") }
                    }
                },
            )
            AppScreen.Annotations -> com.moyue.reader.feature.annotations.AnnotationCenter(container, { screen = AppScreen.Shelf }, { id, anchor -> openContent(id, anchor = anchor) })
            AppScreen.Search -> com.moyue.reader.feature.annotations.GlobalSearch(container, { screen = AppScreen.Shelf }, { id, anchor -> openContent(id, anchor = anchor) })
            AppScreen.Settings -> SettingsScreen(
                preferences = preferences,
                onPreferences = { value -> scope.launch { container.preferences.update { value } } },
                onClearCache = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            container.applicationContext.cacheDir.resolve("books").deleteRecursively()
                            container.storage.ensureRoots()
                        }
                        snackbar.showSnackbar("缓存已清理，源文件和阅读记录仍保留")
                    }
                },
                onBookshelf = { screen = AppScreen.Shelf },
                updateState = updateState,
                onAutomaticUpdates = container.updates::setAutomatic,
                onCheckUpdates = container.updates::checkManually,
                onOpenUpdate = container.updates::showPrompt,
            )
            is AppScreen.Reader -> ReaderDestination(
                destination,
                container,
                preferences,
                onBack = { screen = AppScreen.Shelf },
                onPreferences = { value -> scope.launch { container.preferences.update { value } } },
            )
            is AppScreen.Markdown -> com.moyue.reader.feature.markdown.MarkdownScreen(
                bookId = destination.bookId, container = container, preferences = preferences,
                startEditing = destination.edit, onBack = { screen = AppScreen.Shelf },
                initialAnchor = destination.anchor,
                onPreferences = { value -> scope.launch { container.preferences.update { value } } },
            )
            is AppScreen.Pdf -> com.moyue.reader.feature.pdf.PdfScreen(
                bookId=destination.bookId, container=container, preferences=preferences,
                initialAnchor=destination.anchor,
                onPreferences={value -> scope.launch {container.preferences.update {value}}}, onBack={screen=AppScreen.Shelf},
            )
            is AppScreen.Visual -> com.moyue.reader.feature.image.VisualScreen(destination.bookId, container, preferences, { value -> scope.launch {container.preferences.update {value}} }, {screen=AppScreen.Shelf}, initialAnchor=destination.anchor)
            is AppScreen.Detail -> DetailDestination(
                bookId = destination.bookId,
                books = books,
                container = container,
                onBack = { screen = AppScreen.Shelf },
                onRead = { openContent(destination.bookId) },
                onDeleted = { screen = AppScreen.Shelf },
            )
            is AppScreen.Browser -> SafeWebViewScreen(
                initialUrl = destination.url,
                onBack = { screen = AppScreen.Shelf; showImport = true },
                onPureMode = { page ->
                    screen = AppScreen.Shelf
                    pendingWebUrl = page.finalUrl
                    showImport = true
                    webBusy = true
                    webError = null
                    scope.launch {
                        runCatching { container.importService.previewWeb(page) }
                            .onSuccess { preview ->
                                showImport = false
                                val result = container.importService.importWeb(preview) { importState = it }
                                if (result is ImportState.Completed) {
                                    importState = null
                                    screen = AppScreen.Reader(result.bookId)
                                } else importState = result
                                webPreview = null
                            }
                            .onFailure { webError = it.message ?: "网页解析失败，请稍后重试" }
                        webBusy = false
                    }
                },
            )
        }
        }
        SnackbarHost(snackbar)
        if ((screen == AppScreen.Shelf || screen == AppScreen.Settings) && !showImport && importState == null) {
            com.moyue.reader.feature.update.AppUpdateDialogs(container.updates)
        }
        if (showImport) {
            ImportSheet(
                initialUrl = pendingWebUrl,
                preview = webPreview,
                webBusy = webBusy,
                webError = webError,
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onPickAnyFile = { filePicker.launch(arrayOf("*/*")) },
                onNewMarkdown = {
                    showImport = false
                    scope.launch {
                        val result = container.importService.newMarkdown()
                        if (result is ImportState.Completed) screen = AppScreen.Markdown(result.bookId, true)
                        else importState = result
                    }
                },
                onAnalyzeWeb = { url ->
                    pendingWebUrl = url
                    webBusy = true
                    webError = null
                    webPreview = null
                    scope.launch {
                        runCatching { container.importService.previewWeb(url) }
                            .onSuccess { preview ->
                                showImport = false
                                val result = container.importService.importWeb(preview) { importState = it }
                                if (result is ImportState.Completed) {
                                    importState = null
                                    screen = AppScreen.Reader(result.bookId)
                                } else importState = result
                                webPreview = null
                            }
                            .onFailure { webError = it.message ?: "网页解析失败，请检查地址后重试" }
                        webBusy = false
                    }
                },
                onOpenBrowser = { url -> showImport = false; screen = AppScreen.Browser(url) },
                onAddWeb = {
                    webPreview?.let { preview ->
                        showImport = false
                        scope.launch {
                            importState = container.importService.importWeb(preview) { importState = it }
                            webPreview = null
                        }
                    }
                },
                onDismiss = { showImport = false; webPreview = null; webError = null },
            )
        }
        importState?.let { state ->
            ImportProgressDialog(state, onDismiss = { importState = null }, onRead = {
                importState = null
                if(state is ImportState.Completed) openContent(state.bookId)
            })
        }
    }
}

@Composable
private fun ReaderDestination(
    destination: AppScreen.Reader,
    container: MoyueContainer,
    preferences: ReaderPreferences,
    onBack: () -> Unit,
    onPreferences: (ReaderPreferences) -> Unit,
) {
    val store = remember(destination.bookId) { androidx.lifecycle.ViewModelStore() }
    val viewModel = remember(store) {
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                requireNotNull(modelClass.cast(ReaderViewModel(ReaderRepository(RoomReaderDataSource(container.database, container.storage, webFetcher=container.webPages)))))
        }
        androidx.lifecycle.ViewModelProvider(store, factory)[ReaderViewModel::class.java]
    }
    androidx.compose.runtime.DisposableEffect(store) { onDispose { store.clear() } }
    val state by viewModel.state.collectAsState()
    val openError by viewModel.openError.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(destination.bookId, destination.chapterIndex, destination.anchor) {
        viewModel.open(destination.bookId, destination.chapterIndex)
        viewModel.updatePreferences(preferences)
        destination.anchor?.let { viewModel.jumpToAnchor(it) }
    }
    LaunchedEffect(preferences) { viewModel.updatePreferences(preferences) }
    BackHandler {
        scope.launch { viewModel.flushProgressOnBack(); onBack() }
    }
    var showCatalogSource by remember {mutableStateOf(false)}
    var annotationDraft by remember { mutableStateOf<com.moyue.reader.feature.annotations.AnnotationDraft?>(null) }
    var showAnnotations by remember { mutableStateOf(false) }
    val annotations by container.database.annotationDao().forBook(destination.bookId).collectAsState(initial = emptyList())
    LaunchedEffect(state?.scrollWindow) { state?.scrollWindow?.forEach { container.searchIndexer.chapterLoaded(destination.bookId, it) } }
    LaunchedEffect(state?.jumpHighlight) { if(state?.jumpHighlight != null) { kotlinx.coroutines.delay(2500); viewModel.clearJumpHighlight() } }
    var catalogSource by remember {mutableStateOf("")}
    var catalogSourceError by remember {mutableStateOf<String?>(null)}
    val exportDiagnostic=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {uri->
        if(uri!=null)scope.launch {
            withContext(Dispatchers.IO) {
                val file=container.storage.webCache(destination.bookId).resolve("catalog-diagnostic.txt")
                container.applicationContext.contentResolver.openOutputStream(uri)?.use {output->
                    output.write((if(file.isFile)file.readText() else "还没有抓取诊断，请先刷新目录。\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    if(showCatalogSource)androidx.compose.material3.AlertDialog(
        onDismissRequest={showCatalogSource=false},
        title={androidx.compose.material3.Text("目录来源")},
        text={androidx.compose.foundation.layout.Column {
            androidx.compose.material3.Text("填写完整目录网址。留空则重新自动识别。")
            androidx.compose.material3.OutlinedTextField(catalogSource,{catalogSource=it;catalogSourceError=null},singleLine=true,label={androidx.compose.material3.Text("完整目录网址")})
            catalogSourceError?.let {androidx.compose.material3.Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={androidx.compose.material3.TextButton(onClick={
            val value=catalogSource.trim()
            val valid=value.isEmpty() || runCatching {java.net.URI(value).let {it.scheme?.lowercase() in setOf("http","https") && !it.host.isNullOrBlank() && it.userInfo==null}}.getOrDefault(false)
            if(!valid)catalogSourceError="请填写有效的HTTP/HTTPS网址"
            else scope.launch {
                withContext(Dispatchers.IO) {
                    val file=container.storage.bookDirectory(destination.bookId).resolve("web-catalog.url")
                    if(value.isEmpty())file.delete() else {file.parentFile?.mkdirs();file.writeText(value.substringBefore('#'))}
                }
                showCatalogSource=false;viewModel.refreshCatalog()
            }
        }) {androidx.compose.material3.Text("保存并刷新")}},
        dismissButton={androidx.compose.material3.TextButton(onClick={showCatalogSource=false}) {androidx.compose.material3.Text("取消")}},
    )
    val current = state
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (openError == null) CircularProgressIndicator()
            else androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(requireNotNull(openError))
                androidx.compose.material3.TextButton(onClick = { scope.launch { viewModel.open(destination.bookId, destination.chapterIndex); viewModel.updatePreferences(preferences) } }) { androidx.compose.material3.Text("重试") }
                androidx.compose.material3.TextButton(onClick = { scope.launch { viewModel.open(destination.bookId, 0); viewModel.updatePreferences(preferences) } }) { androidx.compose.material3.Text("从第一章打开") }
                androidx.compose.material3.TextButton(onClick = onBack) { androidx.compose.material3.Text("返回书架") }
            }
        }
    } else {
        ReaderScreen(
            state = current,
            annotations = annotations + listOfNotNull(current.jumpHighlight?.let { raw ->
                val a=org.json.JSONObject(raw)
                com.moyue.reader.core.database.AnnotationEntity(id=-1,bookId=current.bookId,type="HIGHLIGHT",anchorJson=raw,selectedText=a.optString("quote"),note="",color="yellow",location="",sourceHash=com.moyue.reader.feature.annotations.textHash(com.moyue.reader.feature.annotations.blockText(current.chapter.blocks[a.optInt("blockIndex")])),createdAt=0,updatedAt=0)
            }),
            onSelection = { chapter, start, end, type -> annotationDraft = com.moyue.reader.feature.annotations.textDraft(current.bookId, chapter, start, end, type) },
            onAnnotations = { showAnnotations = true },
            onBookmark = { annotationDraft = com.moyue.reader.feature.annotations.textDraft(current.bookId, current.chapter, current.position, current.position, "BOOKMARK") },
            onBack = { scope.launch { viewModel.flushProgressOnBack(); onBack() } },
            onToggleControls = viewModel::toggleControls,
            onPrevious = { scope.launch { viewModel.goPrevious() } },
            onNext = { scope.launch { viewModel.goNext() } },
            onChapter = { scope.launch { viewModel.goTo(it) } },
            onPosition = viewModel::updatePosition,
            onScrollPosition = viewModel::updateScrollPosition,
            onPrefetch = { scope.launch { viewModel.prefetchFollowing() } },
            onRefreshCatalog = { scope.launch { viewModel.refreshCatalog() } },
            onCatalogSource = {scope.launch {
                catalogSource=withContext(Dispatchers.IO) {
                    val file=container.storage.bookDirectory(destination.bookId).resolve("web-catalog.url")
                    if(file.isFile)file.readText() else ""
                };catalogSourceError=null;showCatalogSource=true
            }},
            onCatalogDiagnostic = {exportDiagnostic.launch("墨阅-目录诊断-${destination.bookId}.txt")},
            onPreferences = { viewModel.updatePreferences(it); onPreferences(it) },
        )
        com.moyue.reader.feature.annotations.AnnotationTools(container, destination.bookId, annotationDraft, { annotationDraft = it }, showAnnotations, { showAnnotations = it }, { a -> scope.launch { viewModel.jumpToAnchor(a.anchorJson) } })
    }
}

@Composable
private fun DetailDestination(
    bookId: Long,
    books: List<BookEntity>,
    container: MoyueContainer,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onDeleted: () -> Unit,
) {
    val book = books.firstOrNull { it.id == bookId }
    var chapters by remember(bookId) { mutableStateOf<List<ChapterEntity>>(emptyList()) }
    var documentMetadata by remember(bookId) { mutableStateOf<com.moyue.reader.core.database.DocumentMetadataEntity?>(null) }
    var imageSize by remember(bookId) {mutableStateOf<String?>(null)}
    val scope = rememberCoroutineScope()
    LaunchedEffect(bookId) { chapters = container.database.chapterDao().forBook(bookId); documentMetadata = container.database.documentDao().metadata(bookId)
        if(documentMetadata?.format=="IMAGE") withContext(Dispatchers.IO) {
            val source=container.database.bookDao().get(bookId)?.sourcePath?.let {java.io.File(it)}
            source?.let {runCatching {com.moyue.reader.feature.image.ImageProbe.file(it)}.getOrNull()}?.let {imageSize="${it.width} × ${it.height}"}
        }
    }
    if (book == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    BookDetailScreen(
        book,
        chapters,
        onBack,
        onRead,
        onDelete = {
            scope.launch {
                deleteShelfBook(container, bookId)
                onDeleted()
            }
        },
        documentMetadata = documentMetadata,
        imageSize = imageSize,
    )
}

private suspend fun deleteShelfBook(container: MoyueContainer, bookId: Long) = withContext(Dispatchers.IO) {
    val book = container.database.bookDao().get(bookId) ?: return@withContext
    val files = container.storage.bookDirectory(bookId)
    val cache = container.storage.epubCache(bookId).parentFile
    check(!files.exists() || files.deleteRecursively()) { "无法删除书籍副本" }
    check(cache == null || !cache.exists() || cache.deleteRecursively()) { "无法删除书籍缓存" }
    container.database.bookDao().delete(book)
    container.database.searchDao().prune()
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
