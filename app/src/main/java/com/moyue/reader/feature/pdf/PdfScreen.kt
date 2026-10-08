package com.moyue.reader.feature.pdf

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moyue.reader.core.ui.MoyueMotion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.document.PdfPosition
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderTheme
import com.moyue.reader.core.ui.MoyueReaderTheme
import com.moyue.reader.core.ui.ReaderDisplayEffects
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(bookId: Long, container: MoyueContainer, preferences: ReaderPreferences, onPreferences: (ReaderPreferences)->Unit, onBack: ()->Unit, initialAnchor: String? = null) {
    val context=LocalContext.current
    val window=LocalActivity.current?.window
    val view=LocalView.current
    val density=LocalDensity.current.density
    var topChrome by remember { mutableFloatStateOf(0f) }
    var bottomChrome by remember { mutableFloatStateOf(0f) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    var document by remember(bookId) { mutableStateOf<PdfDocument?>(null) }
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    var web by remember(bookId) { mutableStateOf<WebView?>(null) }
    var opened by remember(bookId) { mutableStateOf(false) }
    var passwordPrompt by remember(bookId) {mutableStateOf(false)}
    var page by remember(bookId) { mutableIntStateOf(1) }
    var pages by remember(bookId) { mutableIntStateOf(0) }
    var position by remember(bookId) { mutableStateOf(PdfPosition()) }
    var controls by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf("") }
    var jump by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf("") }
    var sidebarView by remember { mutableIntStateOf(0) }
    var outlineCount by remember { mutableIntStateOf(0) }
    var backPending by remember { mutableStateOf(false) }
    val session=remember(bookId) { UUID.randomUUID().toString() }
    val snackbar=remember { SnackbarHostState() }
    var annotationDraft by remember { mutableStateOf<com.moyue.reader.feature.annotations.AnnotationDraft?>(null) }
    var showAnnotations by remember { mutableStateOf(false) }
    val annotations by container.database.annotationDao().forBook(bookId).collectAsState(initial = emptyList())
    val savedBookmarks by container.database.documentDao().bookmarks(bookId).collectAsState(initial=emptyList())
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    ReaderDisplayEffects(preferences)
    DisposableEffect(window,view) {
        val controller=window?.let { WindowCompat.getInsetsController(it,view) }
        val oldBehavior=controller?.systemBarsBehavior
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if(oldBehavior!=null) controller?.systemBarsBehavior=oldBehavior
        }
    }
    LaunchedEffect(controls,window) {
        window?.let {
            val controller=WindowCompat.getInsetsController(it,view)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(controls) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun js(method:String, data:String="") { web?.evaluateJavascript("window.MoyuePdf && MoyuePdf.$method($data)",null) }
    fun notify(text:String) { scope.launch { snackbar.showSnackbar(text) } }
    fun closeSearch() { search=false; focus.clearFocus(); keyboard?.hide(); js("stopFind") }
    fun closePanels(closeSidebar: Boolean = true) {
        menu=false; appearance=false; bookmarks=false; jump=false
        closeSearch()
        if(closeSidebar) { sidebarView=0; js("closeSidebar") }
    }
    fun toggleSidebar(view: Int) { closePanels(closeSidebar=false); controls=true; js("sidebar",view.toString()) }
    fun toggleSearch() { val show=!search; closePanels(); search=show; controls=true }
    fun showBookmarks() { closePanels(); controls=true; bookmarks=true }
    fun showAppearance() { closePanels(); controls=true; appearance=true }
    fun showJump() { closePanels(); controls=true; pageText=page.toString(); jump=true }
    fun find() {
        focus.clearFocus(); keyboard?.hide()
        if(query.isBlank()) {matches="请输入要查找的文字"; js("stopFind")}
        else { matches="正在查找…"; js("find",JSONObject.quote(query)) }
    }
    fun back() {
        when {
            menu -> menu=false
            jump -> jump=false
            bookmarks -> bookmarks=false
            appearance -> appearance=false
            search -> closeSearch()
            sidebarView!=0 -> { sidebarView=0; js("closeSidebar") }
            opened && !backPending -> { backPending=true; js("snapshot") }
            !opened -> onBack()
        }
    }
    BackHandler { back() }
    LaunchedEffect(bookId) {
        runCatching { container.documents.open(bookId) }.onSuccess { document=it; position=it.position; page=it.position.pageIndex+1 }
            .onFailure { error=it.message ?: "PDF 打开失败" }
    }
    DisposableEffect(lifecycle,web) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP && opened) js("snapshot") }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(backPending) { if(backPending) { delay(1500); if(backPending) { document?.let { container.documents.save(it,position,pages) }; onBack() } } }
    LaunchedEffect(search,query,opened) {
        if(search && opened) {
            if(query.isBlank()) { matches="请输入要查找的文字"; js("stopFind") }
            else { delay(300); matches="正在查找…"; js("find",JSONObject.quote(query)) }
        }
    }
    val messageHandler: (String)->Unit = handler@{ raw ->
        if(raw.length > 3*1024*1024) return@handler
        val data=runCatching { JSONObject(raw) }.getOrNull() ?: return@handler
        val type=data.optString("type")
        if(type!="ready" && data.optString("session")!=session) return@handler
        when(type) {
            "annotationMissing" -> notify("旧书签位置无法解析，记录仍保留")
            "annotation" -> {
                val anchor = data.optJSONObject("anchor") ?: return@handler
                annotationDraft = com.moyue.reader.feature.annotations.AnnotationDraft(bookId, anchor.toString(), data.optString("text"), "第 ${anchor.optInt("pageIndex") + 1} 页", document?.hash.orEmpty(), data.optString("annotationType", "HIGHLIGHT"))
            }
            "indexedPage" -> container.documentWrites.launch { container.searchIndexer.pdfPage(bookId, data.optInt("page"), data.optString("text")) }
            "ready" -> document?.let { doc ->
                js("open",JSONObject().put("session",session).put("position",if(doc.hasPosition) JSONObject(PdfRepository.encode(doc.position)) else JSONObject())
                    .put("mode",preferences.pdfPageMode).put("fit",preferences.pdfFit).toString())
            }
            "opened" -> { opened=true; pages=data.optInt("pages"); error=null }
            "password" -> passwordPrompt=data.optBoolean("open")
            "error" -> error=data.optString("message","PDF 打开失败")
            "page" -> page=data.optInt("page",1)
            "position" -> {
                position=PdfRepository.decode(data.toString()).safe(pages); page=position.pageIndex+1
                val doc=document; val current=position; val count=pages; val exit=backPending
                val eventTime=android.os.SystemClock.elapsedRealtimeNanos()
                if(doc!=null && count>0) container.documentWrites.launch {
                    val saved=runCatching { container.documents.save(doc,current,count,eventTime) }.isSuccess
                    withContext(Dispatchers.Main) {
                        if(!saved) {backPending=false; notify("阅读位置保存失败，请重试")}
                        else if(exit) {backPending=false; onBack()}
                    }
                }
            }
            "sidebar" -> sidebarView=data.optInt("view")
            "tap" -> when {
                search -> closeSearch()
                sidebarView!=0 -> { sidebarView=0; js("closeSidebar") }
                !menu && !appearance && !bookmarks && !jump -> controls=!controls
            }
            "matches" -> matches=if(data.optInt("total")>0) "${data.optInt("current")} / ${data.optInt("total")}" else if(data.optInt("state")==3) "正在查找…" else "未找到匹配；扫描页需要 OCR"
            "outline" -> outlineCount=data.optInt("count")
            "cover" -> document?.let { doc -> container.documentWrites.launch { runCatching { container.documents.cover(doc,Base64.decode(data.optString("image").substringAfter(','),Base64.DEFAULT)) } } }
        }
    }
    val currentMessage by rememberUpdatedState(messageHandler)
    val latestOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(opened, annotations) { if(opened) js("annotations", container.annotations.json(annotations)) }
    LaunchedEffect(opened, initialAnchor) { if(opened && initialAnchor != null) js("jumpAnnotation", initialAnchor) }
    MoyueReaderTheme(preferences) {
        val background=MaterialTheme.colorScheme.background
        val color="#%06X".format(background.toArgb() and 0xffffff)
        LaunchedEffect(web,preferences.theme,preferences.customColors,preferences.backgroundHex,preferences.pdfInvert,opened) {
            js("appearance",JSONObject().put("background",color).put("night",background.luminance()<.5f).put("invert",preferences.pdfInvert).toString())
        }
        LaunchedEffect(opened,controls,topChrome,bottomChrome) {
            js("chrome",JSONObject().put("top",if(controls) topChrome else 0f).put("bottom",if(controls) bottomChrome else 0f)
                .put("reduceMotion",android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f).toString())
        }
        // One full-window WebView stays mounted. Native chrome only overlays it, never resizes it.
        Box(Modifier.fillMaxSize().background(background)) {
            val doc=document
            Box(Modifier.fillMaxSize()) {
                    if(doc!=null && error==null) AndroidView(modifier=Modifier.fillMaxSize(),factory={ ctx ->
                        WebView(ctx).apply {
                            layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            web=this; val host=PdfResourceHost(ctx,doc.source)
                            settings.javaScriptEnabled=true; settings.allowFileAccess=false; settings.allowContentAccess=false
                            settings.domStorageEnabled=false; settings.setSupportZoom(false)
                            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { error="请更新 Android System WebView 后再打开 PDF" }
                            else WebViewCompat.addWebMessageListener(this,"MoyuePdfBridge",setOf(PDF_ORIGIN)) { _,message,origin,main,_ -> if(main && origin.toString()==PDF_ORIGIN) currentMessage(message.data ?: "") }
                            webViewClient=object : WebViewClient() {
                                override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest)=host.response(request)
                                override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean {
                                    if(request.url.toString().startsWith("$PDF_ORIGIN/assets/pdfjs/")) return false
                                    if(request.hasGesture() && request.url.scheme in setOf("https","http")) runCatching {ctx.startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                                    return true
                                }
                                override fun onReceivedError(view:WebView?,request:WebResourceRequest,e:WebResourceError) { if(request.isForMainFrame) error="PDF 组件加载失败，请更新 System WebView" }
                                override fun onRenderProcessGone(view:WebView?,detail:RenderProcessGoneDetail?):Boolean { error="PDF 页面内存不足，请返回后重新打开"; return true }
                            }
                            loadUrl("$PDF_ORIGIN/assets/pdfjs/web/viewer.html")
                        }
                    },onRelease={ it.stopLoading(); WebViewCompat.removeWebMessageListener(it,"MoyuePdfBridge"); it.destroy(); web=null })
                    if(!opened && !passwordPrompt && error==null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if(error!=null) Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(error!!); TextButton({latestOnBack()}) { Text("返回书架") }
                    }
            }
            AnimatedVisibility(controls,enter=MoyueMotion.enter(-1),exit=MoyueMotion.exit(-1),modifier=Modifier.align(Alignment.TopCenter)) {
              Column(Modifier.fillMaxWidth()) {
              TopAppBar(modifier=Modifier.onSizeChanged {topChrome=it.height/density},title={Text(document?.book?.title ?: "PDF",maxLines=1)},navigationIcon={IconButton({back()}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回书架") }},actions={
                IconButton({val show=!menu; closePanels(); menu=show}) { Icon(Icons.Default.MoreVert,"PDF 菜单") }
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("添加当前位置书签")},enabled=opened,onClick={menu=false; annotationDraft = com.moyue.reader.feature.annotations.AnnotationDraft(bookId, JSONObject().put("kind","PDF").put("pageIndex",position.pageIndex).put("position",JSONObject(PdfRepository.encode(position))).toString(), "", "第 ${position.pageIndex + 1} 页", type="BOOKMARK")})
                    DropdownMenuItem(text={Text("本书标注")},onClick={menu=false; showAnnotations=true})
                    if(outlineCount>0) DropdownMenuItem(text={Text("文档大纲")},onClick={toggleSidebar(2)})
                    DropdownMenuItem(text={Text("阅读外观")},onClick={showAppearance()})
                }
            },colors=TopAppBarDefaults.topAppBarColors(containerColor=background))
                AnimatedVisibility(search,enter=MoyueMotion.expand(),exit=MoyueMotion.collapse()) {
                  Surface(Modifier.fillMaxWidth(),color=background,shadowElevation=4.dp) {
                    Column(Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
                        OutlinedTextField(query,{query=it},label={Text("查找文档文字")},singleLine=true,modifier=Modifier.fillMaxWidth(),
                            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={find()}))
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            TextButton({find()}) {Text("搜索")}
                            TextButton({js("nextMatch","true")},enabled=query.isNotBlank()) {Text("上一个")}
                            TextButton({js("nextMatch","false")},enabled=query.isNotBlank()) {Text("下一个")}
                            TextButton({closeSearch()}) {Text("关闭")}
                        }
                        Text(matches,style=MaterialTheme.typography.labelSmall)
                    }
                }
                }
              }
            }
            AnimatedVisibility(controls,enter=MoyueMotion.enter(1),exit=MoyueMotion.exit(1),modifier=Modifier.align(Alignment.BottomCenter)) {
              Column(Modifier.fillMaxWidth().background(background).navigationBarsPadding().onSizeChanged {bottomChrome=it.height/density}) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    TextButton({js("page",(page-1).toString())},enabled=opened && page>1) { Text("上一页") }
                    TextButton({showJump()},enabled=opened) { Text("$page / ${if(pages>0) pages else "—"}") }
                    TextButton({js("page",(page+1).toString())},enabled=opened && page<pages) { Text("下一页") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.Center) {
                    TextButton({toggleSidebar(1)},enabled=opened) { Text("缩略图") }
                    TextButton({toggleSearch()},enabled=opened) { Text("查找") }
                    TextButton({showBookmarks()},enabled=opened) { Text("书签") }
                    TextButton({showAppearance()}) { Text("外观") }
                    TextButton({js("zoom","-1")},enabled=opened) { Text("−") }
                    TextButton({js("zoom","1")},enabled=opened) { Text("＋") }
                }
              }
            }
            SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(controls) 104.dp else 12.dp))
        }
        if(jump) AlertDialog(onDismissRequest={jump=false},title={Text("跳转页码（1–$pages）")},text={OutlinedTextField(pageText,{pageText=it.filter(Char::isDigit).take(8)},singleLine=true)},confirmButton={TextButton({val n=pageText.toIntOrNull(); if(n!=null && n in 1..pages) {js("page",n.toString()); jump=false} else notify("请输入 1 到 $pages 的页码")}) {Text("跳转")}},dismissButton={TextButton({jump=false}) {Text("取消")}})
        if(bookmarks) ModalBottomSheet(onDismissRequest={bookmarks=false}) {
            Text("我的书签",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(20.dp))
            if(savedBookmarks.isEmpty()) Text("暂无书签，可从菜单添加当前位置",modifier=Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)) { items(savedBookmarks,key={it.id}) { mark -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton({bookmarks=false; js("restore",mark.positionJson)},modifier=Modifier.weight(1f)) {Text(mark.title)}
                TextButton({scope.launch {container.database.documentDao().deleteBookmark(mark.id)}}) {Text("删除")}
            } } }
            Spacer(Modifier.height(24.dp))
        }
        if(appearance) ModalBottomSheet(onDismissRequest={appearance=false}) {
            Text("PDF 阅读外观",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) { ReaderTheme.entries.forEach { theme -> FilterChip(selected=preferences.theme==theme,onClick={onPreferences(preferences.copy(theme=theme))},label={Text(when(theme) {ReaderTheme.DAY->"日间";ReaderTheme.SEPIA->"纸色";ReaderTheme.GREEN->"护眼";ReaderTheme.GRAY->"灰色";ReaderTheme.NIGHT->"夜间"})},modifier=Modifier.padding(4.dp)) } }
            Row { listOf("continuous" to "连续滚动","single" to "单页").forEach { (value,label) -> FilterChip(selected=preferences.pdfPageMode==value,onClick={onPreferences(preferences.copy(pdfPageMode=value)); js("mode",JSONObject.quote(value))},label={Text(label)},modifier=Modifier.padding(6.dp)) } }
            Row { listOf("page-width" to "适宽","page-fit" to "适屏").forEach { (value,label) -> FilterChip(selected=preferences.pdfFit==value,onClick={onPreferences(preferences.copy(pdfFit=value)); js("fit",JSONObject.quote(value))},label={Text(label)},modifier=Modifier.padding(6.dp)) } }
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { Text("页面反色",Modifier.weight(1f)); Switch(preferences.pdfInvert,{onPreferences(preferences.copy(pdfInvert=it))}) }
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { Text("保持屏幕常亮",Modifier.weight(1f)); Switch(preferences.keepScreenOn,{onPreferences(preferences.copy(keepScreenOn=it))}) }
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { Text("跟随系统亮度",Modifier.weight(1f)); Switch(preferences.brightness<0,{onPreferences(preferences.copy(brightness=if(it) -1f else .5f))}) }
            if(preferences.brightness>=0) Slider(preferences.brightness,{onPreferences(preferences.copy(brightness=it))},valueRange=.05f..1f,modifier=Modifier.padding(horizontal=20.dp))
            Text("主题、亮度和常亮与书籍同步；缩放和阅读方式单独保存。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(20.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
    com.moyue.reader.feature.annotations.AnnotationTools(container, bookId, annotationDraft, { annotationDraft = it }, showAnnotations, { showAnnotations = it }, { a -> js("jumpAnnotation", a.anchorJson) })
}
