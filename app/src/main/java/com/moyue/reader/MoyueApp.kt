package com.moyue.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
    data class Reader(val bookId: Long, val chapterIndex: Int? = null) : AppScreen
    data class Detail(val bookId: Long) : AppScreen
    data class Browser(val url: String) : AppScreen
}

@Composable
fun MoyueApp(container: MoyueContainer, sharedUrl: String? = null) {
    val books by container.database.bookDao().observeAll().collectAsState(initial = emptyList())
    val preferences by container.preferences.preferences.collectAsState(initial = ReaderPreferences())
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

    MoyueTheme {
        BackHandler(enabled = screen != AppScreen.Shelf && screen !is AppScreen.Reader) { screen = AppScreen.Shelf }
        when (val destination = screen) {
            AppScreen.Shelf -> BookshelfScreen(
                books = books,
                onOpenBook = { screen = AppScreen.Reader(it) },
                onBookDetails = { screen = AppScreen.Detail(it) },
                onAddBook = { showImport = true },
                onSettings = { screen = AppScreen.Settings },
            )
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
            )
            is AppScreen.Reader -> ReaderDestination(
                destination,
                container,
                preferences,
                onBack = { screen = AppScreen.Shelf },
                onPreferences = { value -> scope.launch { container.preferences.update { value } } },
            )
            is AppScreen.Detail -> DetailDestination(
                bookId = destination.bookId,
                books = books,
                container = container,
                onBack = { screen = AppScreen.Shelf },
                onRead = { chapter -> screen = AppScreen.Reader(destination.bookId, chapter) },
                onDeleted = { screen = AppScreen.Shelf },
            )
            is AppScreen.Browser -> SafeWebViewScreen(
                initialUrl = destination.url,
                onBack = { screen = AppScreen.Shelf; showImport = true },
                onPureMode = { url ->
                    screen = AppScreen.Shelf
                    pendingWebUrl = url
                    showImport = true
                    webBusy = true
                    webError = null
                    scope.launch {
                        runCatching { container.importService.previewWeb(url) }
                            .onSuccess { webPreview = it }
                            .onFailure { webError = it.message ?: "网页解析失败，请稍后重试" }
                        webBusy = false
                    }
                },
            )
        }
        SnackbarHost(snackbar)
        if (showImport) {
            ImportSheet(
                initialUrl = pendingWebUrl,
                preview = webPreview,
                webBusy = webBusy,
                webError = webError,
                onPickFile = { filePicker.launch(arrayOf("text/plain", "application/epub+zip")) },
                onAnalyzeWeb = { url ->
                    pendingWebUrl = url
                    webBusy = true
                    webError = null
                    webPreview = null
                    scope.launch {
                        runCatching { container.importService.previewWeb(url) }
                            .onSuccess { webPreview = it }
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
        importState?.let { state -> ImportProgressDialog(state) { importState = null } }
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
    val viewModel = remember(destination.bookId) {
        ReaderViewModel(ReaderRepository(RoomReaderDataSource(container.database, container.storage)))
    }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(destination.bookId, destination.chapterIndex) {
        viewModel.open(destination.bookId, destination.chapterIndex)
    }
    LaunchedEffect(preferences) { viewModel.updatePreferences(preferences) }
    BackHandler {
        scope.launch { viewModel.flushProgress(); onBack() }
    }
    val current = state
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        ReaderScreen(
            state = current,
            onBack = { scope.launch { viewModel.flushProgress(); onBack() } },
            onToggleControls = viewModel::toggleControls,
            onPrevious = { scope.launch { viewModel.goPrevious() } },
            onNext = { scope.launch { viewModel.goNext() } },
            onChapter = { scope.launch { viewModel.goTo(it) } },
            onPosition = viewModel::updatePosition,
            onPreferences = { viewModel.updatePreferences(it); onPreferences(it) },
        )
    }
}

@Composable
private fun DetailDestination(
    bookId: Long,
    books: List<BookEntity>,
    container: MoyueContainer,
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    onDeleted: () -> Unit,
) {
    val book = books.firstOrNull { it.id == bookId }
    var chapters by remember(bookId) { mutableStateOf<List<ChapterEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(bookId) { chapters = container.database.chapterDao().forBook(bookId) }
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
                withContext(Dispatchers.IO) {
                    container.storage.bookDirectory(bookId).deleteRecursively()
                    container.storage.epubCache(bookId).parentFile?.deleteRecursively()
                    container.database.bookDao().delete(book)
                }
                onDeleted()
            }
        },
    )
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
