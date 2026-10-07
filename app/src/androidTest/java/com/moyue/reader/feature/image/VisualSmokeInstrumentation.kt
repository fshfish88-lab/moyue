package com.moyue.reader.feature.image

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.document.*
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

class VisualSmokeInstrumentation:Instrumentation() {
    private var suite:String?=null
    private var ids:String?=null
    override fun onCreate(arguments:Bundle?) {suite=arguments?.getString("suite");ids=arguments?.getString("ids");super.onCreate(arguments);start()}
    override fun onStart() {
        val result=Bundle();var checks=0
        fun verify(b:Boolean,label:String) {check(b){label};checks++}
        try {runBlocking {
            lateinit var c:com.moyue.reader.MoyueContainer
            runOnMainSync {c=(targetContext.applicationContext as MoyueApplication).container}
            if(suite=="cleanup") {
                for(id in ids.orEmpty().split(',').mapNotNull(String::toLongOrNull)) {
                    val book=c.database.bookDao().get(id) ?: continue
                    require(book.title in listOf("image","long","huge","static","exif","comic","images","comic100"))
                    val hash=VisualRepository.hash(File(book.sourcePath))
                    require(listOf("image.png","long.jpg","huge.jpg","static.webp","exif.jpg","comic.cbz","images.zip","comic100.cbz").any {name ->context.assets.open("visual-fixtures/$name").use {java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b)}}==hash})
                    c.database.bookDao().delete(book);c.storage.bookDirectory(id).deleteRecursively();c.storage.visualCache(id).deleteRecursively()
                }
                result.putString("stream","PASS owned visual fixtures cleanup");finish(-1,result);return@runBlocking
            }
            val original=c.database.bookDao().observeAll().first()
            val oldFiles=original.associate {it.id to VisualRepository.hash(File(it.sourcePath))}
            val imported=JSONObject()
            for(name in listOf("image.png","long.jpg","huge.jpg","static.webp","exif.jpg","comic.cbz","images.zip","comic100.cbz")) {
                val input=targetContext.cacheDir.resolve("visual-test/$name");input.parentFile!!.mkdirs()
                context.assets.open("visual-fixtures/$name").use {s->input.outputStream().use {s.copyTo(it)}}
                val state=c.importService.importDocument(Uri.fromFile(input),name)
                verify(state is ImportState.Completed,"import $name: $state")
                val id=(state as ImportState.Completed).bookId;imported.put(name,id)
                val doc=c.visuals.open(id)
                verify(doc.hash==VisualRepository.hash(input),"source exact $name")
                verify(c.database.chapterDao().forBook(id).isEmpty(),"no fake chapters $name")
                verify(File(doc.book.coverPath!!).isFile,"real cover $name")
                verify(ReaderEngineRegistry.item(doc.book).format==if(doc.comic)DocumentFormat.COMIC else DocumentFormat.IMAGE,"registry $name")
                if(name=="exif.jpg")verify(doc.pages[0].width==600 && doc.pages[0].height==1200,"EXIF dimensions")
                if(name=="comic.cbz")verify(doc.pages.map {it.name}==listOf("chapter/1.jpg","chapter/2.jpg","chapter/10.jpg"),"natural page order")
            }
            val count=c.database.bookDao().count()
            for(name in listOf("traversal.cbz","duplicates.cbz","office.zip","bad-image.cbz","bomb.cbz","empty.cbz","fake.png","broken.png")) {
                val input=targetContext.cacheDir.resolve("visual-test/$name");input.parentFile!!.mkdirs()
                context.assets.open("visual-fixtures/$name").use {s->input.outputStream().use {s.copyTo(it)}}
                verify(c.importService.importDocument(Uri.fromFile(input),name) !is ImportState.Completed,"reject $name")
                verify(c.database.bookDao().count()==count,"no residual row $name")
            }
            val doc=c.visuals.open(imported.getLong("comic.cbz"))
            val position=ImagePosition(2,"chapter/10.jpg","single","width",.3f,2f,.4f,.6f,90)
            c.visuals.save(doc,position)
            c.visuals.save(doc,ImagePosition(),event=1L)
            verify(c.visuals.open(doc.book.id).position==position,"persist all logical state; ignore late write")
            c.visuals.bookmark(doc,position)
            val mark=c.database.documentDao().bookmarks(doc.book.id).first().first()
            verify(VisualRepository.decode(mark.positionJson)==position,"bookmark logical state")
            c.database.documentDao().deleteBookmark(mark.id)
            verify(c.database.documentDao().bookmarks(doc.book.id).first().isEmpty(),"delete bookmark")
            c.visuals.page(doc,2)
            c.storage.visualCache(doc.book.id).deleteRecursively()
            verify(c.visuals.open(doc.book.id).position==position,"clear cache preserves position")
            verify(c.visuals.page(doc,2).isFile,"cache rebuilt on demand")
            verify(doc.source.isFile,"source retained after clearing cache")
            val longDoc=c.visuals.open(imported.getLong("comic100.cbz"))
            for(i in 0..11)c.visuals.page(longDoc,i)
            val cached=c.storage.visualCache(longDoc.book.id).walkTopDown().filter {it.isFile}.toList()
            verify(cached.size<=8 && cached.sumOf {it.length()}<=96L*1024*1024,"cache bounded for 100 pages")
            val image=c.visuals.open(imported.getLong("image.png"));c.visuals.save(image,ImagePosition(scale=2f,rotation=90))
            verify(c.database.bookDao().get(image.book.id)!!.progress==0f,"single image has no fake completion")
            val prefs=c.preferences.preferences.first();c.preferences.update {it.copy(comicPageMode="single",imageFit="width")}
            val after=c.preferences.preferences.first()
            verify(after.pdfFit==prefs.pdfFit && after.pdfPageMode==prefs.pdfPageMode && after.fontSizeSp==prefs.fontSizeSp,"dedicated preferences")
            c.preferences.update {prefs}
            for(book in original){verify(c.database.bookDao().get(book.id)==book,"old row unchanged ${book.id}");verify(VisualRepository.hash(File(book.sourcePath))==oldFiles[book.id],"old source unchanged ${book.id}")}
            verify(c.database.openHelper.readableDatabase.version==2,"schema preserved")
            verify(c.storage.importTemp("probe").parentFile!!.listFiles().orEmpty().isEmpty(),"failed import staging cleaned")
            result.putString("fixtures",imported.toString())
        };result.putString("stream","PASS visual checks=$checks");finish(-1,result)
        }catch(e:Throwable){result.putString("stream","FAIL ${e.stackTraceToString()}");finish(0,result)}
    }
}
