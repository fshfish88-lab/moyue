package com.moyue.reader.feature.image

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.CoilZoomState
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.moyue.reader.MoyueContainer
import com.moyue.reader.core.document.ImagePosition
import com.moyue.reader.core.settings.ReaderPreferences
import com.moyue.reader.core.settings.ReaderTheme
import com.moyue.reader.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File

@Composable
fun VisualScreen(bookId:Long,container:MoyueContainer,preferences:ReaderPreferences,onPreferences:(ReaderPreferences)->Unit,onBack:()->Unit) {
    var document by remember(bookId) {mutableStateOf<VisualDocument?>(null)}
    var error by remember(bookId) {mutableStateOf<String?>(null)}
    LaunchedEffect(bookId) {
        try {document=container.visuals.open(bookId)} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message ?: "打开失败"}
    }
    MoyueReaderTheme(preferences) {
        ReaderDisplayEffects(preferences)
        val doc=document
        when {
            error!=null -> {BackHandler(onBack=onBack);Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Text(error!!);TextButton(onBack){Text("返回书架")}}}
            doc==null -> {BackHandler(onBack=onBack);Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
            else -> LoadedVisual(doc,container,preferences,onPreferences,onBack)
        }
    }
}

private enum class Panel { MENU, THUMBNAILS, BOOKMARKS, APPEARANCE, JUMP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedVisual(doc:VisualDocument,container:MoyueContainer,preferences:ReaderPreferences,onPreferences:(ReaderPreferences)->Unit,onBack:()->Unit) {
    val initial=remember(doc) {if(doc.hasPosition) doc.position else doc.position.copy(mode=if(doc.comic) preferences.comicPageMode else "single",fit=preferences.imageFit)}
    var position by remember(doc) {mutableStateOf(initial.safe(doc.pages))}
    var mode by remember(doc) {mutableStateOf(if(doc.comic) position.mode else "single")}
    var fit by remember(doc) {mutableStateOf(position.fit)}
    var page by remember(doc) {mutableIntStateOf(position.pageIndex)}
    var controls by remember {mutableStateOf(true)}
    var panel by remember {mutableStateOf<Panel?>(null)}
    var pageText by remember {mutableStateOf("")}
    var exitPending by remember {mutableStateOf(false)}
    var initialized by remember {mutableStateOf(false)}
    var activeZoom by remember {mutableStateOf<CoilZoomState?>(null)}
    var zoomGeneration by remember {mutableIntStateOf(0)}
    val list=rememberLazyListState(initialFirstVisibleItemIndex=page)
    val pager=rememberPagerState(initialPage=page,pageCount={doc.pages.size})
    val scope=rememberCoroutineScope();val snackbar=remember {SnackbarHostState()}
    val bookmarks by container.database.documentDao().bookmarks(doc.book.id).collectAsState(emptyList())
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val window=LocalActivity.current?.window;val view=LocalView.current
    val current by rememberUpdatedState(position)
    fun notify(message:String) {scope.launch {snackbar.showSnackbar(message)}}
    fun tap() {if(panel!=null) panel=null else controls=!controls}
    fun toggle(next:Panel) {val same=panel==next;panel=if(same) null else next;controls=true}
    fun flush() {
        val snapshot=position.safe(doc.pages);val event=android.os.SystemClock.elapsedRealtimeNanos()
        container.documentWrites.launch {runCatching {container.visuals.save(doc,snapshot,event)}.onFailure {android.util.Log.e("MoyueImage","保存位置失败",it)}}
    }
    fun back() {
        if(panel!=null) {panel=null;return}
        if(exitPending)return
        exitPending=true;val snapshot=position.safe(doc.pages);val event=android.os.SystemClock.elapsedRealtimeNanos()
        scope.launch {
            try {container.visuals.save(doc,snapshot,event);onBack()} catch(e:CancellationException){throw e} catch(e:Exception){exitPending=false;notify("位置保存失败，请重试")}
        }
    }
    fun jump(target:Int,restore:ImagePosition?=null) {
        if(target !in doc.pages.indices)return
        panel=null;initialized=false;activeZoom=null
        position=(restore ?: position.copy(pageIndex=target,entryName=doc.pages[target].name,listOffset=0f,scale=1f,centerX=.5f,centerY=.5f,rotation=0)).safe(doc.pages)
        page=target;fit=position.fit;mode=if(doc.comic)position.mode else "single";zoomGeneration++
    }
    BackHandler {back()}
    DisposableEffect(window,view) {
        val controller=window?.let {WindowCompat.getInsetsController(it,view)};val old=controller?.systemBarsBehavior
        onDispose {controller?.show(WindowInsetsCompat.Type.systemBars());if(old!=null)controller?.systemBarsBehavior=old}
    }
    LaunchedEffect(controls,window) {
        window?.let {val c=WindowCompat.getInsetsController(it,view);c.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(controls)c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())}
    }
    DisposableEffect(lifecycle,doc) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {
            val snapshot=current;val time=android.os.SystemClock.elapsedRealtimeNanos()
            container.documentWrites.launch {runCatching {container.visuals.save(doc,snapshot,time)}}
        }}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);val snapshot=current;val time=android.os.SystemClock.elapsedRealtimeNanos();container.documentWrites.launch {runCatching {container.visuals.save(doc,snapshot,time)}}}
    }
    LaunchedEffect(mode,zoomGeneration) {
        initialized=false;activeZoom=null
        if(mode=="continuous") {
            list.scrollToItem(page)
            if(position.listOffset>0) {
                snapshotFlow {list.layoutInfo.visibleItemsInfo.firstOrNull {it.index==page}?.size}.filterNotNull().first().let {height->list.scrollToItem(page,(position.listOffset*height).toInt())}
            }
        } else pager.scrollToPage(page)
        initialized=true
    }
    LaunchedEffect(mode,initialized) {
        if(!initialized)return@LaunchedEffect
        if(mode=="continuous") snapshotFlow {
            val item=list.layoutInfo.visibleItemsInfo.firstOrNull {it.index in doc.pages.indices}
            item?.let {it.index to (-it.offset.toFloat()/it.size.coerceAtLeast(1)).coerceIn(0f,1f)}
        }.filterNotNull().distinctUntilChanged().collect {(index,offset)->
            if(!initialized)return@collect
            if(index!=page){activeZoom=null;page=index;position=position.copy(pageIndex=index,entryName=doc.pages[index].name,scale=1f,rotation=0,centerX=.5f,centerY=.5f)}
            position=position.copy(mode=mode,fit=fit,listOffset=offset)
        } else snapshotFlow {pager.currentPage}.distinctUntilChanged().collect {index->
            if(!initialized)return@collect
            if(index!=page){activeZoom=null;page=index;position=position.copy(pageIndex=index,entryName=doc.pages[index].name,mode=mode,fit=fit,listOffset=0f,scale=1f,rotation=0,centerX=.5f,centerY=.5f)}
        }
    }
    LaunchedEffect(doc,initialized) {
        if(initialized) snapshotFlow {position}.distinctUntilChanged().collectLatest {p->delay(400);container.visuals.save(doc,p)}
    }
    LaunchedEffect(Unit) {flush()}
    val background=MaterialTheme.colorScheme.background
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val contentWidth=maxWidth
        val maximumRowHeight=maxHeight*8
        if(mode=="continuous" && doc.comic) {
            LazyColumn(state=list,modifier=Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                itemsIndexed(doc.pages,key={_,p->p.name}) {index,p->
                    val height=(contentWidth*(p.height.toFloat()/p.width)).coerceIn(72.dp,maximumRowHeight)
                    key(doc.hash,index,zoomGeneration,fit) {
                        ZoomPage(doc,index,container,Modifier.fillMaxWidth().height(height),fit,position.takeIf {index==page},index==page,
                            onTap={tap()},onActive={if(index==page)activeZoom=it},onPosition={p->if(index==page && initialized)position=p.copy(mode=mode,fit=fit,listOffset=position.listOffset)})
                    }
                }
                // Keep the final page alignable at the top when it is shorter than the viewport.
                // The filler ends at the viewport bottom, so scrolling cannot hide the final page.
                val last=doc.pages.last()
                val lastHeight=(contentWidth*(last.height.toFloat()/last.width)).coerceIn(72.dp,maximumRowHeight)
                val remaining=(maxHeight-lastHeight-4.dp).coerceAtLeast(0.dp)
                if(remaining>0.dp)item(key="visual-end") {Spacer(Modifier.height(remaining))}
            }
        } else {
            HorizontalPager(state=pager,modifier=Modifier.fillMaxSize(),userScrollEnabled=doc.comic && (activeZoom?.zoomable?.userTransform?.scaleX ?: 1f)<=1.01f,beyondViewportPageCount=0) {index->
                key(doc.hash,index,zoomGeneration,fit) {
                    ZoomPage(doc,index,container,Modifier.fillMaxSize(),fit,position.takeIf {index==page},index==page,
                        onTap={tap()},onActive={if(index==page)activeZoom=it},onPosition={p->if(index==page && initialized)position=p.copy(mode=mode,fit=fit,listOffset=0f)})
                }
            }
        }
        AnimatedVisibility(controls,enter=MoyueMotion.enter(-1),exit=MoyueMotion.exit(-1),modifier=Modifier.align(Alignment.TopCenter)) {
            TopAppBar(title={Text(doc.book.title,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton({back()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回书架")}},
                actions={IconButton({toggle(Panel.MENU)}){Icon(Icons.Default.MoreVert,"图片漫画菜单")}
                    DropdownMenu(panel==Panel.MENU,{panel=null}) {
                        if(doc.comic) DropdownMenuItem(text={Text("添加当前位置书签")},onClick={panel=null;scope.launch {runCatching {container.visuals.bookmark(doc,position)}.onSuccess {notify("书签已添加")}.onFailure {notify("添加书签失败")}}})
                        DropdownMenuItem(text={Text("阅读外观")},onClick={panel=Panel.APPEARANCE})
                        DropdownMenuItem(text={Text("重置缩放与旋转")},onClick={panel=null;position=position.copy(scale=1f,rotation=0,centerX=.5f,centerY=.5f);zoomGeneration++})
                    }
                },colors=TopAppBarDefaults.topAppBarColors(containerColor=background))
        }
        AnimatedVisibility(controls,enter=MoyueMotion.enter(1),exit=MoyueMotion.exit(1),modifier=Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(background).navigationBarsPadding()) {
                if(doc.comic) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    TextButton({jump(page-1)},enabled=page>0){Text("上一页")}
                    TextButton({pageText=(page+1).toString();toggle(Panel.JUMP)}){Text("${page+1} / ${doc.pages.size}")}
                    TextButton({jump(page+1)},enabled=page<doc.pages.lastIndex){Text("下一页")}
                } else Text("${doc.pages[0].width} × ${doc.pages[0].height}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.align(Alignment.CenterHorizontally).padding(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.Center) {
                    if(doc.comic) {TextButton({toggle(Panel.THUMBNAILS)}){Text("缩略图")};TextButton({toggle(Panel.BOOKMARKS)}){Text("书签")}}
                    TextButton({toggle(Panel.APPEARANCE)}){Text("外观")}
                    TextButton({scope.launch {activeZoom?.zoomable?.rotateBy(90)}}){Text("旋转")}
                    TextButton({scope.launch {activeZoom?.zoomable?.scaleBy(.8f,animated=true)}}){Text("−")}
                    TextButton({scope.launch {activeZoom?.zoomable?.scaleBy(1.25f,animated=true)}}){Text("＋")}
                }
            }
        }
        // This scrim consumes the first outside tap. It never reaches the image or page navigation.
        AnimatedVisibility(panel==Panel.THUMBNAILS,enter=MoyueMotion.enter(),exit=MoyueMotion.exit()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=.3f)).clickable {panel=null})
                Surface(Modifier.width(272.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding(),color=background,shadowElevation=8.dp) {
                    Column {
                        Row(verticalAlignment=Alignment.CenterVertically) {Text("缩略图",Modifier.weight(1f).padding(16.dp),style=MaterialTheme.typography.titleLarge);TextButton({panel=null}){Text("关闭")}}
                        LazyColumn {itemsIndexed(doc.pages,key={_,p->p.name}) {i,p->
                            Row(Modifier.fillMaxWidth().clickable {jump(i)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Thumbnail(doc,i,container,Modifier.width(72.dp).height(96.dp))
                                Column(Modifier.weight(1f).padding(start=12.dp)) {Text("第 ${i+1} 页");Text(p.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)}
                            }
                        }}
                    }
                }
            }
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(controls) 112.dp else 12.dp))
        if(exitPending) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    if(panel==Panel.JUMP) AlertDialog(onDismissRequest={panel=null},title={Text("跳转页码（1–${doc.pages.size}）")},text={OutlinedTextField(pageText,{pageText=it.filter(Char::isDigit).take(6)},singleLine=true,label={Text("页码")})},confirmButton={TextButton({val n=pageText.toIntOrNull();if(n!=null && n in 1..doc.pages.size)jump(n-1) else notify("请输入有效页码")}){Text("跳转")}},dismissButton={TextButton({panel=null}){Text("取消")}})
    if(panel==Panel.BOOKMARKS) ModalBottomSheet(onDismissRequest={panel=null}) {
        Text("我的书签",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(20.dp))
        if(bookmarks.isEmpty()) Text("暂无书签，可从菜单添加当前位置",modifier=Modifier.padding(20.dp))
        LazyColumn(Modifier.heightIn(max=420.dp)) {items(bookmarks,key={it.id}){b->Row(verticalAlignment=Alignment.CenterVertically){TextButton({
            if(runCatching {JSONObject(b.positionJson).optString("sourceHash")==doc.hash}.getOrDefault(false)) {
                val restored=VisualRepository.decode(b.positionJson).safe(doc.pages);jump(restored.pageIndex,restored)
            } else notify("原文件已变化，无法恢复此书签")
        },Modifier.weight(1f)){Text(b.title)};TextButton({scope.launch {container.database.documentDao().deleteBookmark(b.id)}}){Text("删除")}}}}
        Spacer(Modifier.height(24.dp))
    }
    if(panel==Panel.APPEARANCE) ModalBottomSheet(onDismissRequest={panel=null}) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
            Text(if(doc.comic) "漫画阅读外观" else "图片阅读外观",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {ReaderTheme.entries.forEach {theme->FilterChip(selected=preferences.theme==theme,onClick={onPreferences(preferences.copy(theme=theme))},label={Text(when(theme){ReaderTheme.DAY->"日间";ReaderTheme.SEPIA->"纸色";ReaderTheme.GREEN->"护眼";ReaderTheme.GRAY->"灰色";ReaderTheme.NIGHT->"夜间"})},modifier=Modifier.padding(4.dp))}}
            if(doc.comic) Row {listOf("continuous" to "连续滚动","single" to "单页翻页").forEach {(v,label)->FilterChip(selected=mode==v,onClick={position=position.copy(mode=v,listOffset=0f,scale=1f,rotation=0);mode=v;onPreferences(preferences.copy(comicPageMode=v))},label={Text(label)},modifier=Modifier.padding(6.dp))}}
            Row {listOf("screen" to "适屏","width" to "适宽").forEach {(v,label)->FilterChip(selected=fit==v,onClick={position=position.copy(fit=v,scale=1f,rotation=0,centerX=.5f,centerY=.5f);fit=v;onPreferences(preferences.copy(imageFit=v))},label={Text(label)},modifier=Modifier.padding(6.dp))}}
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("保持屏幕常亮",Modifier.weight(1f));Switch(preferences.keepScreenOn,{onPreferences(preferences.copy(keepScreenOn=it))})}
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("跟随系统亮度",Modifier.weight(1f));Switch(preferences.brightness<0,{onPreferences(preferences.copy(brightness=if(it)-1f else .5f))})}
            if(preferences.brightness>=0) Slider(preferences.brightness,{onPreferences(preferences.copy(brightness=it))},valueRange=.05f..1f,modifier=Modifier.padding(horizontal=20.dp))
            Text("单击图片显隐工具栏，双击或双指缩放；缩放后拖动查看细节。主题只改变背景，保留图片原色。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun ZoomPage(doc:VisualDocument,index:Int,container:MoyueContainer,modifier:Modifier,fit:String,restore:ImagePosition?,active:Boolean,onTap:()->Unit,onActive:(CoilZoomState)->Unit,onPosition:(ImagePosition)->Unit) {
    val context=LocalContext.current
    val zoom=rememberCoilZoomState()
    var file by remember(doc,index) {mutableStateOf<File?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var restored by remember {mutableStateOf(false)}
    val latestPosition by rememberUpdatedState(onPosition)
    val latestActive by rememberUpdatedState(onActive)
    val baseline=remember {restore}
    LaunchedEffect(doc,index) {
        var acquired:File?=null
        try {acquired=withContext(NonCancellable){container.visuals.page(doc,index,pin=true)};file=acquired;awaitCancellation()}
        catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "页面读取失败"}
        finally {acquired?.let {withContext(NonCancellable){container.visuals.release(it)}}}
    }
    LaunchedEffect(zoom) {zoom.zoomable.setAnimationSpec(com.github.panpf.zoomimage.compose.zoom.ZoomAnimationSpec(MoyueMotion.Standard,MoyueMotion.Easing))}
    LaunchedEffect(active,restored) {if(active && restored)latestActive(zoom)}
    LaunchedEffect(file) {
        if(file==null)return@LaunchedEffect
        snapshotFlow {zoom.zoomable.contentSize.width>0 && zoom.zoomable.containerSize.width>0}.first {it}
        val p=baseline
        if(p!=null) {
            if(p.rotation!=0){zoom.zoomable.rotate(p.rotation);delay(80)}
            if(p.scale>1.01f || p.centerX!=.5f || p.centerY!=.5f) zoom.zoomable.locate(Offset(zoom.zoomable.contentSize.width*p.centerX,zoom.zoomable.contentSize.height*p.centerY),targetScale=zoom.zoomable.baseTransform.scaleX*p.scale)
        }
        restored=true
    }
    LaunchedEffect(active,restored) {
        if(!active || !restored)return@LaunchedEffect
        snapshotFlow {
            val z=zoom.zoomable;val size=z.contentSize;val center=z.contentVisibleRectF.center
            ImagePosition(index,doc.pages[index].name,fit=fit,scale=z.userTransform.scaleX,centerX=center.x/size.width.coerceAtLeast(1),centerY=center.y/size.height.coerceAtLeast(1),rotation=z.transform.rotation.toInt())
        }.distinctUntilChanged().collect {latestPosition(it)}
    }
    Box(modifier) {
        val local=file
        // A long image with FillWidth can otherwise decode a full-height hardware bitmap
        // exceeding API 26's GL texture limit. This bounded overview is supplemented by
        // ZoomImage's original-file region tiles when zooming into the image.
        val request=remember(local,context) {local?.let {ImageRequest.Builder(context).data(it).size(2048,2048).scale(Scale.FIT).precision(Precision.EXACT).allowHardware(false).build()}}
        if(local!=null) CoilZoomAsyncImage(model=request,contentDescription="第 ${index+1} 页图片",modifier=Modifier.fillMaxSize(),zoomState=zoom,
            contentScale=if(fit=="width") ContentScale.FillWidth else ContentScale.Fit,alignment=if(fit=="width")Alignment.TopCenter else Alignment.Center,
            onTap={onTap()},onError={error="图片显示失败，请返回后重试"})
        if(local==null && error==null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if(error!=null) Text(error!!,Modifier.align(Alignment.Center).clickable {onTap()}.padding(20.dp))
    }
}

@Composable
private fun Thumbnail(doc:VisualDocument,index:Int,container:MoyueContainer,modifier:Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,doc,index) {
        value=withContext(Dispatchers.IO) {runCatching {
            if(doc.comic) ImageProbe.preview(com.moyue.reader.core.document.ComicArchive.page(doc.source,doc.pages[index]),192)
            else android.graphics.BitmapFactory.decodeFile(doc.source.absolutePath,ImageProbe.options(doc.pages[index].width,doc.pages[index].height,192))
        }.getOrNull()}
    }
    // Compose/RenderThread may retain a display list during the exit animation. Small preview
    // bitmaps are owned by the painter and GC; recycling on composition disposal is unsafe.
    Box(modifier,contentAlignment=Alignment.Center){bitmap?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)} ?: Text("${index+1}")}
}
