package com.moyue.reader.feature.markdown

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import com.moyue.reader.core.ui.MoyueMotion
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.settings.*
import com.moyue.reader.core.ui.MoyueReaderTheme
import com.moyue.reader.core.ui.ReaderDisplayEffects
import com.moyue.reader.core.ui.palette
import com.moyue.reader.feature.reader.ReaderSettingsSheet
import com.moyue.reader.parser.markdown.MarkdownText
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

private const val ORIGIN = "https://appassets.androidplatform.net"
private class PageBridge(private val deliver: (String) -> Unit) {
    @JavascriptInterface fun post(message: String) { if (message.length <= 7 * 1024 * 1024) deliver(message) }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownScreen(
    bookId: Long,
    container: MoyueContainer,
    preferences: ReaderPreferences,
    startEditing: Boolean = false,
    onPreferences: (ReaderPreferences) -> Unit,
    onBack: () -> Unit,
    initialAnchor: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val repository = container.markdown
    var document by remember(bookId) { mutableStateOf<MarkdownDocument?>(null) }
    var title by remember(bookId) { mutableStateOf("") }
    var fatal by remember(bookId) { mutableStateOf<String?>(null) }
    var status by remember(bookId) { mutableStateOf("正在打开…") }
    var editing by remember(bookId) { mutableStateOf(false) }
    var loaded by remember(bookId) { mutableStateOf(false) }
    var web by remember(bookId) { mutableStateOf<WebView?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmEditing by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf("") }
    var outline by remember { mutableStateOf(JSONArray()) }
    var exportZip by remember { mutableStateOf(false) }
    var latestRevision by remember(bookId) { mutableLongStateOf(0) }
    var latestText by remember(bookId) { mutableStateOf("") }
    var saveJob by remember(bookId) { mutableStateOf<Job?>(null) }
    val gate = remember(bookId) { MarkdownRevisionGate() }
    val session = remember(bookId) { UUID.randomUUID().toString() }
    val snackbar = remember { SnackbarHostState() }
    var annotationDraft by remember { mutableStateOf<com.moyue.reader.feature.annotations.AnnotationDraft?>(null) }
    var showAnnotations by remember { mutableStateOf(false) }
    val annotations by container.database.annotationDao().forBook(bookId).collectAsState(initial = emptyList())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fontScale = LocalDensity.current.fontScale
    ReaderDisplayEffects(preferences)
    fun js(method: String, payload: String = "") { web?.evaluateJavascript("window.MoyuePage && MoyuePage.$method($payload)", null) }
    fun closeSearch() { showSearch=false; web?.clearMatches(); focus.clearFocus(); keyboard?.hide() }
    fun closePanels() { closeSearch(); menu=false; showOutline=false; showAppearance=false }
    fun toggleSearch() { val show=!showSearch; closePanels(); showSearch=show; if(show) web?.findAllAsync(query) }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.export(bookId, uri, false) }.onSuccess { notify("Markdown 已导出") }.onFailure { notify("导出失败：${it.message}") }
        }
    }
    val exportBundle = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.export(bookId, uri, true) }.onSuccess { notify("文档与图片已导出") }.onFailure { notify("导出失败：${it.message}") }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.insertImage(bookId, uri) }.onSuccess { js("insert", JSONObject.quote(it)) }.onFailure { notify(it.message ?: "图片读取失败") }
        }
    }

    fun afterAction(action: String) {
        when (action) {
            "back" -> onBack()
            "export" -> if (exportZip) exportBundle.launch(MarkdownText.safeName(title) + ".zip") else exportMd.launch(MarkdownText.safeName(title) + ".md")
            "share" -> scope.launch {
                runCatching {
                    val file = context.cacheDir.resolve("markdown-share/${UUID.randomUUID()}/${MarkdownText.safeName(title)}.zip")
                    withContext(Dispatchers.IO) { file.parentFile?.mkdirs() }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    repository.export(bookId, uri, true)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Markdown", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享 Markdown"))
                }.onFailure { notify("分享失败：${it.message}") }
            }
        }
    }

    fun request(action: String) {
        if (!loaded) return
        if (editing) js("save", JSONObject.quote(action)) else js("action", JSONObject.quote(action))
    }

    val onMessage: (String) -> Unit = message@{ raw ->
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@message
        val type = data.optString("type")
        if (type != "ready" && data.optString("session") != session) return@message
        when (type) {
            "annotation" -> {
                val anchor = data.optJSONObject("anchor") ?: return@message
                annotationDraft = com.moyue.reader.feature.annotations.AnnotationDraft(bookId, anchor.toString(), data.optString("text"), "Markdown 正文", com.moyue.reader.feature.annotations.textHash(latestText), data.optString("annotationType", "HIGHLIGHT"))
            }
            "annotationMissing" -> notify("原文位置已变化，无法唯一定位；摘录仍保留")
            "ready" -> document?.let { doc ->
                js("open", JSONObject().put("session", session).put("text", doc.text).put("position", doc.state)
                    .put("edit", startEditing && doc.recovery == null && MarkdownText.canEdit(doc.text)).toString())
            }
            "dismissSearch" -> closeSearch()
            "opened" -> { loaded = true; status = "已保存" }
            "mode" -> editing = data.optBoolean("editing")
            "outline" -> outline = data.optJSONArray("items") ?: JSONArray()
            "changed" -> {
                latestRevision = data.getLong("revision"); latestText = data.getString("text"); status = "正在保存…"
                saveJob?.cancel()
                val rev = latestRevision; val text = latestText
                saveJob = scope.launch {
                    delay(800)
                    try {
                        repository.save(bookId, text, rev, gate)
                        if (rev == latestRevision) { status = "已保存"; js("saved", JSONObject().put("revision", rev).put("text", text).toString()) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { if (rev == latestRevision) status = "保存失败，点击重试"; notify(error.message ?: "保存失败") }
                }
            }
            "save" -> {
                saveJob?.cancel()
                val rev = data.getLong("revision"); val text = data.getString("text"); val action = data.optString("action")
                scope.launch {
                    try {
                        if (rev > 0) repository.save(bookId, text, rev, gate)
                        if (rev != latestRevision) { js("save", JSONObject.quote(action)); return@launch }
                        status = "已保存"
                        js("saved", JSONObject().put("revision", rev).put("text", text).put("action", action).toString())
                        afterAction(action)
                    } catch (error: Exception) { status = "保存失败，点击重试"; notify(error.message ?: "保存失败，请重试") }
                }
            }
            "checkpoint" -> scope.launch {
                runCatching { repository.checkpoint(bookId, data.getString("text"), data.getLong("revision"), gate) }
                    .onFailure { status = "草稿保存失败" }
            }
            "position" -> scope.launch { runCatching { repository.savePosition(bookId, data) } }
            "readAction" -> scope.launch {
                runCatching { repository.savePosition(bookId, data) }
                afterAction(data.optString("action"))
            }
            "image" -> pickImage.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
            "copy" -> { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代码", data.optString("text"))); notify("已复制") }
            "link" -> {
                val uri = Uri.parse(data.optString("url")); if (uri.scheme in setOf("https", "http")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            "busy" -> notify(data.optString("message"))
            "error" -> { status = "页面加载异常"; notify(data.optString("message").take(120)) }
        }
    }
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(bookId) {
        runCatching { repository.open(bookId) }.onSuccess { document = it; title = it.book.title; latestText = it.text }
            .onFailure { fatal = it.message ?: "无法打开文档" }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { js("record"); if (editing) js("save", JSONObject.quote("")) } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(bookId) { onDispose { web?.removeJavascriptInterface("Moyue"); web?.destroy(); web = null } }
    fun back() {
        when {
            menu -> menu=false
            showSearch -> closeSearch()
            loaded -> request(if(editing) "read" else "back")
            else -> onBack()
        }
    }
    BackHandler { back() }
    LaunchedEffect(loaded,showSearch) { if(loaded) js("searchUI",showSearch.toString()) }
    LaunchedEffect(loaded, editing, annotations) { if(loaded && !editing) js("annotations", container.annotations.json(annotations)) }
    LaunchedEffect(loaded, initialAnchor) { if(loaded && initialAnchor != null) { delay(200); js("jumpAnnotation", initialAnchor) } }

    val bg = if (preferences.customColors) preferences.backgroundHex else "%06X".format(preferences.theme.palette.background.toArgb() and 0xFFFFFF)
    val ink = if (preferences.customColors) preferences.textHex else "%06X".format(preferences.theme.palette.ink.toArgb() and 0xFFFFFF)
    LaunchedEffect(preferences, loaded, fontScale) {
        if (loaded) js("theme", JSONObject().put("paper", "#$bg").put("ink", "#$ink")
            .put("size", "${preferences.fontSizeSp * fontScale}px").put("line", when (preferences.lineSpacing) { SpacingLevel.COMPACT -> "1.45"; SpacingLevel.STANDARD -> "1.7"; SpacingLevel.WIDE -> "2.0" })
            .put("margin", when (preferences.margin) { SpacingLevel.COMPACT -> "12px"; SpacingLevel.STANDARD -> "20px"; SpacingLevel.WIDE -> "28px" })
            .put("paragraph", when (preferences.paragraphSpacing) { SpacingLevel.COMPACT -> "12px"; SpacingLevel.STANDARD -> "20px"; SpacingLevel.WIDE -> "28px" })
            .put("indent", if (preferences.indentParagraphs) "2em" else "0")
            .put("reduceMotion", android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
            .put("dark", Color(0xFF000000.toInt() or bg.toInt(16)).luminance() < .5f)
            .put("font", when (preferences.fontFamily) { ReaderFont.SERIF, ReaderFont.LXGW -> "serif"; ReaderFont.SYSTEM, ReaderFont.SANS -> "sans-serif" }).toString())
    }
    MoyueReaderTheme(preferences) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(title = { Column { Text(title.ifEmpty { "Markdown" }, maxLines = 1); Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { if (editing) js("save", JSONObject.quote("")) }) } },
                        navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                        actions = {
                            TextButton(enabled = loaded, onClick = {
                                closePanels()
                                if (editing) request("read")
                                else if (!MarkdownText.canEdit(latestText)) notify("超过 1 MB 或含大量标题的文档暂以只读方式打开")
                                else confirmEditing = true
                            }) { Text(if (editing) "阅读" else "编辑") }
                            Box {
                                IconButton(onClick = { val show=!menu; closePanels(); menu=show }) { Icon(Icons.Default.MoreVert, "更多") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("本书标注") }, onClick = { menu = false; showAnnotations = true })
                                    DropdownMenuItem(text = { Text("添加当前位置书签") }, enabled = loaded && !editing, onClick = { menu = false; js("bookmark") })
                                    DropdownMenuItem(text = { Text("源码编辑") }, enabled = loaded && MarkdownText.canEdit(latestText), onClick = { closePanels(); js("source") })
                                    DropdownMenuItem(text = { Text("查找正文") }, onClick = { closePanels(); showSearch = true; web?.findAllAsync(query) })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renameText = title; showRename = true })
                                    DropdownMenuItem(text = { Text("导出 .md（不含图片）") }, enabled = loaded, onClick = { menu = false; exportZip = false; request("export") })
                                    DropdownMenuItem(text = { Text("导出文档与图片 ZIP") }, enabled = loaded, onClick = { menu = false; exportZip = true; request("export") })
                                    DropdownMenuItem(text = { Text("分享文档与图片") }, enabled = loaded, onClick = { menu = false; request("share") })
                                }
                            }
                        })
                    AnimatedVisibility(showSearch, enter = MoyueMotion.expand(), exit = MoyueMotion.collapse()) {
                      Column {
                      Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(query, { query = it; web?.findAllAsync(it) }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("查找正文") })
                        TextButton({ web?.findNext(false) }) { Text("↑") }; TextButton({ web?.findNext(true) }) { Text("↓") }
                        TextButton({ closeSearch() }) { Text("关闭") }
                    }
                      Text(matches, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
                      }
                    }
                }
            },
            bottomBar = {
                AnimatedVisibility(!editing, enter = MoyueMotion.expand(), exit = MoyueMotion.collapse()) { Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { closePanels(); showOutline = true }) { Text("大纲") }
                    TextButton(onClick = { closePanels(); showAppearance = true }) { Text("外观") }
                    TextButton(onClick = { toggleSearch() }) { Text("查找") }
                }
            } }, snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val doc = document
            if (fatal != null) Column(Modifier.padding(padding).padding(24.dp)) { Text(fatal!!); TextButton(onBack) { Text("返回书架") } }
            else if (doc == null) Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator() }
            else AndroidView(factory = { ctx ->
                val root = container.storage.bookDirectory(bookId).canonicalFile
                val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .addPathHandler("/document/", WebViewAssetLoader.PathHandler { path ->
                        val file = File(root, path).canonicalFile
                        if (!file.toPath().startsWith(root.toPath()) || !file.isFile || !path.startsWith("assets/"))
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        else WebResourceResponse(when (file.extension.lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "application/octet-stream" }, null, file.inputStream())
                    }).build()
                WebView(ctx).apply {
                    setBackgroundColor(Color.Transparent.toArgb())
                    // Vditor stores editor-mode preferences in localStorage even with content cache disabled.
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (com.moyue.reader.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    addJavascriptInterface(PageBridge { raw -> post { currentMessage(raw) } }, "Moyue")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean { android.util.Log.d("MoyueMarkdown", "${message.message()} @ ${message.lineNumber()}"); return true }
                    }
                    setFindListener { active, count, _ -> matches = if (count == 0) "没有匹配" else "${active + 1} / $count" }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                            if (request.url.scheme == "https" && request.url.host == "appassets.androidplatform.net") {
                                loader.shouldInterceptRequest(request.url)?.let { return it }
                            }
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && request.url.scheme in setOf("https", "http") && request.url.host != "appassets.androidplatform.net") {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            }
                            return true
                        }
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) { handler.cancel() }
                    }
                    web = this; loadUrl("$ORIGIN/assets/markdown/index.html")
                }
            }, modifier = Modifier.fillMaxSize().padding(padding).imePadding())
        }

        if (confirmEditing) AlertDialog(onDismissRequest = { confirmEditing = false }, title = { Text("编辑 Markdown") },
            text = { Text("即时编辑会保留常用 Markdown 内容，但可能整理空行和格式。复杂扩展请使用源码模式；原始导入文件不会被覆盖。") },
            confirmButton = { TextButton({ confirmEditing = false; js("edit") }) { Text("开始编辑") } },
            dismissButton = { TextButton({ confirmEditing = false; js("edit", JSONObject.quote("sv")) }) { Text("源码模式") } })
        document?.recovery?.let { draft ->
            AlertDialog(onDismissRequest = {}, title = { Text("发现未完成的草稿") }, text = { Text("可恢复最近保存的输入，或保留当前正式版本。") },
                confirmButton = { TextButton(enabled = loaded, onClick = { document = document?.copy(recovery = null); js("recover", JSONObject.quote(draft.optString("text"))) }) { Text("恢复草稿") } },
                dismissButton = { TextButton(onClick = { scope.launch { repository.discardDraft(bookId); document = document?.copy(recovery = null) } }) { Text("保留正式版本") } })
        }
        if (showRename) AlertDialog(onDismissRequest = { showRename = false }, title = { Text("文档名称") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = { scope.launch { repository.rename(bookId, renameText); title = MarkdownText.safeName(renameText.removeSuffix(".md")); showRename = false } }) { Text("保存") } },
            dismissButton = { TextButton({ showRename = false }) { Text("取消") } })
        if (showOutline) ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("文档大纲", style = MaterialTheme.typography.titleLarge)
                if (outline.length() == 0) Text("当前文档没有标题", Modifier.padding(vertical = 20.dp))
                for (i in 0 until outline.length()) {
                    val item = outline.getJSONObject(i)
                    Text(item.optString("text"), Modifier.fillMaxWidth().clickable { js("jump", i.toString()); showOutline = false }
                        .padding(start = ((item.optInt("level") - 1).coerceAtLeast(0) * 12).dp, top = 12.dp, bottom = 12.dp))
                }
            }
        }
        if (showAppearance) ReaderSettingsSheet(preferences, onPreferences, { showAppearance = false }, showBookNavigation = false)
    }
    com.moyue.reader.feature.annotations.AnnotationTools(container, bookId, annotationDraft, { annotationDraft = it }, showAnnotations, { showAnnotations = it }, { a -> js("jumpAnnotation", a.anchorJson) })
}
