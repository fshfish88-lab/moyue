package com.moyue.reader.feature.image

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moyue.reader.MoyueApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Real Android accessibility clicks and two-pointer events, against Debug or the signed Release. */
class VisualUiInstrumentation:Instrumentation() {
    private val checks=JSONObject()
    private var regression=false
    override fun onCreate(arguments:Bundle?){regression=arguments?.getString("suite")=="regression";super.onCreate(arguments);start()}
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo){result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let {walk(it)}}
        val root=uiAutomation.rootInActiveWindow
        if(root!=null)walk(root) else uiAutomation.windows.mapNotNull {it.root}.forEach {walk(it)}
        return result
    }
    private fun find(label:String)=nodes().lastOrNull {it.text?.toString()==label || it.contentDescription?.toString()==label}
    private fun waitFor(label:String):AccessibilityNodeInfo {repeat(60){find(label)?.let {return it};SystemClock.sleep(100)};error("UI missing: $label; "+nodes().map {it.text ?: it.contentDescription}.filterNotNull())}
    private fun event(x:Float,y:Float,action:Int,time:Long=SystemClock.uptimeMillis()) {val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(e,true));e.recycle()}
    private fun tap(x:Float,y:Float) {val time=SystemClock.uptimeMillis();event(x,y,MotionEvent.ACTION_DOWN,time);SystemClock.sleep(50);event(x,y,MotionEvent.ACTION_UP,time);SystemClock.sleep(500)}
    private fun gone(label:String) {repeat(40){if(find(label)==null)return;SystemClock.sleep(100)};error("UI still present: $label")}
    private fun click(label:String){
        val n=waitFor(label);var target=n
        while(!target.isClickable && target.parent!=null)target=target.parent
        if(target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(600);return}
        val b=Rect();n.getBoundsInScreen(b);tap(b.exactCenterX(),b.exactCenterY())
    }
    private fun verify(b:Boolean,label:String){check(b){label+"; "+nodes().map {it.text ?: it.contentDescription}.filterNotNull()};checks.put(label,true)}
    private fun open(title:String,ready:String="图片漫画菜单"){click("搜索书籍");var editable:AccessibilityNodeInfo?=null
        for(i in 0..60){editable=nodes().firstOrNull {it.isEditable};if(editable!=null)break;SystemClock.sleep(100)}
        checkNotNull(editable){"search input not ready"}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)})
        SystemClock.sleep(700);click(title);waitFor(ready);SystemClock.sleep(1500)}
    private fun pinch() {
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,count:Int,distance:Float) {
            val properties=Array(count){i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coordinates=Array(count){i->MotionEvent.PointerCoords().apply {x=540f+(if(i==0)-distance else distance);y=1100f;pressure=1f;size=1f}}
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            check(uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        send(MotionEvent.ACTION_DOWN,1,100f);send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,100f)
        for(i in 1..12){send(MotionEvent.ACTION_MOVE,2,100f+i*14);SystemClock.sleep(20)}
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,268f);send(MotionEvent.ACTION_UP,1,268f)
        SystemClock.sleep(1800)
    }
    override fun onStart() {
        val out=Bundle()
        try {
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            lateinit var c:com.moyue.reader.MoyueContainer
            runOnMainSync {c=(targetContext.applicationContext as MoyueApplication).container}
            if(regression) {
                val activity=startActivitySync(Intent(targetContext,com.moyue.reader.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                fun value(expression:String):JSONObject {
                    val result=java.util.concurrent.atomic.AtomicReference("{}")
                    val latch=java.util.concurrent.CountDownLatch(1)
                    runOnMainSync {
                        fun find(v:android.view.View):android.webkit.WebView? {
                            if(v is android.webkit.WebView)return v
                            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let {return it}
                            return null
                        }
                        find(activity.window.decorView)?.evaluateJavascript(expression) {result.set(it);latch.countDown()} ?: latch.countDown()
                    }
                    check(latch.await(3,java.util.concurrent.TimeUnit.SECONDS))
                    return runCatching {JSONObject(org.json.JSONTokener(result.get()).nextValue().toString())}.getOrDefault(JSONObject())
                }
                waitFor("搜索书籍");open("CLR-formulas","更多")
                var md=JSONObject()
                for(i in 0..100){md=value("JSON.stringify({math:document.querySelectorAll('#reading .katex').length,errors:document.querySelectorAll('#reading .katex-error').length})");if(md.optInt("math")==105)break;SystemClock.sleep(200)}
                verify(md.optInt("math")==105 && md.optInt("errors")==0,"existing105FormulasRendered")
                click("返回");waitFor("搜索书籍");open("墨阅 PDF 验证","PDF 菜单")
                var pdf=JSONObject()
                for(i in 0..100){pdf=value("JSON.stringify({pages:window.PDFViewerApplication ? window.PDFViewerApplication.pagesCount : 0})");if(pdf.optInt("pages")==12)break;SystemClock.sleep(200)}
                verify(pdf.optInt("pages")==12,"existingPdf12PagesRendered")
                out.putString("checks",checks.toString());out.putString("stream","PASS existing documents regression checks=${checks.length()}");finish(-1,out);return
            }
            val id=runBlocking {
                val book=c.database.bookDao().observeAll().first().first {it.title=="comic"}
                val doc=c.visuals.open(book.id)
                require(context.assets.open("visual-fixtures/comic.cbz").use {it.readBytes()}.contentEquals(doc.source.readBytes())) {"UI test only uses owned fixture"}
                c.visuals.save(doc,com.moyue.reader.core.document.ImagePosition(2,"chapter/10.jpg","single","width",scale=2f,rotation=90))
                book.id
            }
            startActivitySync(Intent(targetContext,com.moyue.reader.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("搜索书籍");open("comic")
            waitFor("3 / 3");verify(true,"logicalPageRestored")
            click("缩略图");waitFor("关闭");tap(900f,1000f);gone("关闭");waitFor("3 / 3");verify(true,"thumbnailOutsideTapConsumes")
            click("缩略图");waitFor("关闭");uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(400)
            gone("关闭");waitFor("图片漫画菜单");verify(true,"backClosesPanelFirst")
            click("缩略图");click("第 1 页");waitFor("1 / 3");gone("关闭");verify(true,"thumbnailSelectionCloses")
            click("下一页");waitFor("2 / 3");verify(true,"nextPage")
            click("图片漫画菜单");click("添加当前位置书签");click("书签");waitFor("我的书签");waitFor("第 2 页");verify(true,"bookmarkShowsCurrentPage")
            SystemClock.sleep(800)
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);gone("我的书签");SystemClock.sleep(800)
            click("图片漫画菜单");click("重置缩放与旋转");SystemClock.sleep(800)
            pinch();val zoom=runBlocking {c.visuals.open(id).position}
            verify(zoom.scale>1.2f,"twoPointerPinchChangesScale")
            click("旋转");SystemClock.sleep(800);verify(runBlocking {c.visuals.open(id).position.rotation}==90,"rotationPersisted")
            // Rotation recalculates the component's fit scale. Zoom again to arrange a state
            // containing both a rotation and a non-default user scale for the reopen check.
            pinch();verify(runBlocking {c.visuals.open(id).position.scale}>1.2f,"zoomAfterRotation")
            tap(900f,900f);gone("图片漫画菜单");verify(true,"tapHidesChrome");tap(900f,900f);waitFor("图片漫画菜单");verify(true,"tapShowsChrome")
            click("返回书架");waitFor("搜索书籍");open("comic");waitFor("2 / 3");SystemClock.sleep(700)
            val restored=runBlocking {c.visuals.open(id).position};verify(restored.rotation==90 && restored.scale>1.2f,"zoomRotationRestoredAfterReopen")
            click("外观");click("连续滚动");uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(700)
            waitFor("2 / 3");verify(true,"modeKeepsLogicalPage")
            click("下一页");waitFor("3 / 3");verify(true,"continuousJump")
            click("返回书架");waitFor("搜索书籍");open("long");waitFor("1200 × 20000");verify(true,"longImageRendered")
            click("外观");click("适宽");uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(800)
            pinch();verify(true,"longImagePinchNoCrash")
            click("返回书架");waitFor("搜索书籍");open("huge");waitFor("8192 × 8192");pinch();verify(true,"hugeImagePinchNoCrash")
            click("返回书架");waitFor("搜索书籍");open("exif");waitFor("600 × 1200");verify(true,"exifDisplayedOriented")
            out.putString("checks",checks.toString());out.putString("stream","PASS visual UI checks=${checks.length()}");finish(-1,out)
        }catch(e:Throwable){
            out.putString("checks",checks.toString())
            val windows=uiAutomation.windows.map {"${it.type}:${it.isActive}:${it.isFocused}:${it.root?.packageName}"}
            val activity=uiAutomation.executeShellCommand("dumpsys activity activities").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().takeLast(5000)}
            out.putString("stream","FAIL ${e.stackTraceToString()}\nwindows=$windows\n$activity");finish(0,out)
        }
    }
}
