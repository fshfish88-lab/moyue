package com.moyue.reader.feature.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.room.withTransaction
import com.moyue.reader.core.database.*
import com.moyue.reader.core.document.*
import com.moyue.reader.core.model.*
import com.moyue.reader.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class VisualDocument(val book: BookEntity,val source:File,val hash:String,val pages:List<ImagePage>,val position:ImagePosition,val hasPosition:Boolean,val comic:Boolean)

internal class VisualImportProcessor(private val displayName:String?,private val comic:Boolean):ImportProcessor {
    override suspend fun detect(source:File) {
        require(source.length()<=ComicArchive.MAX_SOURCE) { "文件不能超过 200 MB" }
        if(!comic) ImageProbe.file(source)
    }
    override suspend fun parse(source:File,sourceType:SourceType):ParsedBook {
        val job=currentCoroutineContext()
        val pages=if(comic) ComicArchive.scan(source,ImageProbe::inspect) {job.ensureActive()}
            else listOf(ImageProbe.file(source).let {ImagePage("",it.width,it.height)})
        val hash=VisualRepository.hash(source)
        VisualRepository.writeIndex(source.parentFile!!.resolve("visual-index.json"),hash,pages)
        val title=displayName?.substringBeforeLast('.')?.trim()?.takeIf {it.isNotBlank()}?.take(200) ?: if(comic) "漫画" else "图片"
        val cover=runCatching {
            val bitmap=if(comic) ImageProbe.preview(ComicArchive.page(source,pages.first())) else {
                val b=androidx.exifinterface.media.ExifInterface(source)
                val raw=BitmapFactory.decodeFile(source.absolutePath,ImageProbe.options(pages[0].width,pages[0].height,512)) ?: error("封面解码失败")
                val matrix=android.graphics.Matrix().apply {if(b.isFlipped) postScale(-1f,1f); postRotate(b.rotationDegrees.toFloat())}
                Bitmap.createBitmap(raw,0,0,raw.width,raw.height,matrix,true).also {if(it!==raw)raw.recycle()}
            }
            try {source.parentFile!!.resolve("visual-cover.png").also {f->f.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}} finally {bitmap.recycle()}
        }.getOrNull()
        return ParsedBook(title,null,SourceType.DOCUMENT,emptyList(),coverPath=cover?.absolutePath,
            document=DocumentMetadata(if(comic) "COMIC" else "IMAGE",if(comic) "application/vnd.comicbook+zip" else "image/${ImageProbe.signature(source.inputStream().use {val h=ByteArray(32);it.read(h);h})!!.replace("jpg","jpeg")}",source.length(),pages.size))
    }
    override suspend fun validate(book:ParsedBook) { require((book.document?.pageCount ?: 0)>0) }
}

class VisualRepository(private val database:MoyueDatabase,private val storage:BookStorage) {
    private val mutex=Mutex(); private val latest=mutableMapOf<Long,Long>(); private val pins=mutableMapOf<String,Int>()
    private val touches=mutableMapOf<String,Long>(); private var accessClock=0L
    suspend fun open(id:Long)=withContext(Dispatchers.IO) {
        val book=requireNotNull(database.bookDao().get(id)) {"文档不存在"}
        val format=database.documentDao().metadata(id)?.format
        require(book.sourceType==SourceType.DOCUMENT && format in setOf("IMAGE","COMIC")) {"请选择图片或漫画"}
        val source=File(book.sourcePath)
        require(source.isFile && source.canonicalFile==storage.sourceFile(id,source.extension).canonicalFile) {"原始文件不存在"}
        val hash=hash(source); val index=storage.bookDirectory(id).resolve("visual-index.json")
        val job=currentCoroutineContext()
        val pages=readIndex(index,hash) ?: (if(format=="COMIC") ComicArchive.scan(source,ImageProbe::inspect) {job.ensureActive()}
            else listOf(ImageProbe.file(source).let {ImagePage("",it.width,it.height)})).also {writeIndex(index,hash,it)}
        val state=database.documentDao().state(id)?.takeIf {it.engineId=="zoomimage" && it.stateVersion==1 && it.sourceHash==hash}
        VisualDocument(book,source,hash,pages,state?.let {decode(it.positionJson).safe(pages)} ?: ImagePosition(),state!=null,format=="COMIC")
    }
    suspend fun page(doc:VisualDocument,index:Int,pin:Boolean=false):File=withContext(Dispatchers.IO) {mutex.withLock {
        require(index in doc.pages.indices)
        if(!doc.comic) return@withLock doc.source
        val dir=storage.visualCache(doc.book.id).resolve(doc.hash.take(16));dir.mkdirs()
        val file=dir.resolve("$index.${doc.pages[index].name.substringAfterLast('.').lowercase()}")
        if(!file.isFile) {
            val bytes=ComicArchive.page(doc.source,doc.pages[index]); ImageProbe.inspect(bytes)
            atomic(file,bytes)
        }
        file.setLastModified(System.currentTimeMillis())
        touches[file.absolutePath]=++accessClock
        if(pin)pins[file.absolutePath]=(pins[file.absolutePath] ?: 0)+1
        // Bounded disk cache; currently visible/nearby pages can always be rebuilt from the source.
        // API26 filesystems can round mtime to whole seconds. Use a monotonic access sequence
        // so the current page is first even when several writes share the same timestamp.
        val cached=dir.listFiles()?.filter {it.isFile}?.sortedWith(compareByDescending<File> {touches[it.absolutePath] ?: 0L}.thenByDescending {it.lastModified()}).orEmpty()
        var size=0L
        cached.forEachIndexed {i,f ->size+=f.length();if(f!=file && (pins[f.absolutePath] ?: 0)==0 && (i>=8 || size>96L*1024*1024)) {if(f.delete())touches.remove(f.absolutePath)}}
        file
    } }
    suspend fun release(file:File)=mutex.withLock {
        val count=(pins[file.absolutePath] ?: 1)-1
        if(count<=0)pins.remove(file.absolutePath) else pins[file.absolutePath]=count
    }
    suspend fun save(doc:VisualDocument,p:ImagePosition,event:Long=android.os.SystemClock.elapsedRealtimeNanos())=withContext(Dispatchers.IO) {mutex.withLock {
        if(event<(latest[doc.book.id] ?: 0L))return@withLock
        val safe=p.safe(doc.pages); val now=System.currentTimeMillis()
        database.withTransaction {
            if(database.bookDao().get(doc.book.id)==null)return@withTransaction
            database.documentDao().upsertState(DocumentStateEntity(doc.book.id,"zoomimage",1,doc.hash,encode(safe),now))
            database.bookDao().updateReading(doc.book.id,if(!doc.comic || doc.pages.size==1) 0f else safe.pageIndex.toFloat()/(doc.pages.size-1),now)
        }
        latest[doc.book.id]=event
    } }
    suspend fun bookmark(doc:VisualDocument,p:ImagePosition) {
        val safe=p.safe(doc.pages)
        database.documentDao().addBookmark(DocumentBookmarkEntity(bookId=doc.book.id,positionJson=JSONObject(encode(safe)).put("sourceHash",doc.hash).toString(),title="第 ${safe.pageIndex+1} 页",createdAt=System.currentTimeMillis()))
    }
    companion object {
        fun hash(file:File):String {val md=MessageDigest.getInstance("SHA-256");file.inputStream().use {s->val b=ByteArray(65536);while(true){val n=s.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
        fun encode(p:ImagePosition):String=JSONObject().put("pageIndex",p.pageIndex).put("entryName",p.entryName).put("mode",p.mode).put("fit",p.fit).put("listOffset",p.listOffset).put("scale",p.scale).put("centerX",p.centerX).put("centerY",p.centerY).put("rotation",p.rotation).toString()
        fun decode(raw:String):ImagePosition=runCatching {val j=JSONObject(raw);ImagePosition(j.optInt("pageIndex"),j.optString("entryName"),j.optString("mode","continuous"),j.optString("fit","screen"),j.optDouble("listOffset",0.0).toFloat(),j.optDouble("scale",1.0).toFloat(),j.optDouble("centerX",.5).toFloat(),j.optDouble("centerY",.5).toFloat(),j.optInt("rotation"))}.getOrDefault(ImagePosition())
        fun writeIndex(file:File,hash:String,pages:List<ImagePage>) {
            val a=JSONArray();pages.forEach {a.put(JSONObject().put("name",it.name).put("width",it.width).put("height",it.height))}
            atomic(file,JSONObject().put("version",1).put("hash",hash).put("pages",a).toString().toByteArray())
        }
        fun readIndex(file:File,hash:String):List<ImagePage>?=runCatching {
            require(file.length() in 1..2_000_000)
            val j=JSONObject(file.readText());require(j.getInt("version")==1 && j.getString("hash")==hash)
            val a=j.getJSONArray("pages");require(a.length() in 1..ComicArchive.MAX_ENTRIES)
            List(a.length()){i->val p=a.getJSONObject(i);val s=ImageSize(p.getInt("width"),p.getInt("height"));ImagePage(p.getString("name"),s.width,s.height)}
        }.getOrNull()
        private fun atomic(file:File,bytes:ByteArray) {file.parentFile?.mkdirs();val a=AtomicFile(file);val out=a.startWrite();try {out.write(bytes);a.finishWrite(out)}catch(e:Throwable){a.failWrite(out);throw e}}
    }
}
