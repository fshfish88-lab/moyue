package com.moyue.reader.feature.pdf

import androidx.room.withTransaction
import com.moyue.reader.core.database.*
import com.moyue.reader.core.document.PdfPosition
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.core.storage.BookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class PdfDocument(val book: BookEntity, val metadata: DocumentMetadataEntity, val source: File, val hash: String, val position: PdfPosition, val hasPosition: Boolean)
class PdfRepository(private val database: MoyueDatabase, private val storage: BookStorage) {
    private val mutex = Mutex()
    private val latestWrite = mutableMapOf<Long,Long>()
    suspend fun open(id: Long) = withContext(Dispatchers.IO) {
        val book = requireNotNull(database.bookDao().get(id)) { "文档不存在" }
        require(book.sourceType == SourceType.DOCUMENT) { "请使用对应格式的阅读器" }
        val metadata = requireNotNull(database.documentDao().metadata(id))
        require(metadata.format == "PDF") { "暂不支持此文档格式" }
        val source = File(book.sourcePath)
        require(source.isFile && source.canonicalFile == storage.sourceFile(id,"pdf").canonicalFile) { "PDF 源文件不存在" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val state = database.documentDao().state(id)?.takeIf { it.sourceHash == hash && it.engineId == "pdfjs" && it.stateVersion == 1 }
        val position = state?.let { decode(it.positionJson) } ?: PdfPosition()
        PdfDocument(book, metadata, source, hash, position, state!=null)
    }
    suspend fun save(doc: PdfDocument, position: PdfPosition, pageCount: Int, eventTime: Long = android.os.SystemClock.elapsedRealtimeNanos()) = withContext(Dispatchers.IO) { mutex.withLock {
        if(pageCount <= 0) return@withLock
        if(eventTime < (latestWrite[doc.book.id] ?: 0L)) return@withLock
        val safe = position.safe(pageCount); val time = System.currentTimeMillis()
        database.withTransaction {
            if(database.bookDao().get(doc.book.id) == null) return@withTransaction
            database.documentDao().upsertState(DocumentStateEntity(doc.book.id,"pdfjs",1,doc.hash,encode(safe),time))
            database.documentDao().upsertMetadata(doc.metadata.copy(pageCount=pageCount))
            val progress = if(pageCount == 1) 0f else (safe.pageIndex.toFloat()/(pageCount-1)).coerceIn(0f,1f)
            database.bookDao().updateReading(doc.book.id,progress,time)
        }
        latestWrite[doc.book.id]=eventTime
    } }
    suspend fun bookmark(doc: PdfDocument, position: PdfPosition) = database.documentDao().addBookmark(DocumentBookmarkEntity(bookId=doc.book.id,positionJson=encode(position),title="第 ${position.pageIndex+1} 页",createdAt=System.currentTimeMillis()))
    suspend fun cover(doc: PdfDocument, png: ByteArray) = withContext(Dispatchers.IO) { mutex.withLock {
        if(png.size > 2*1024*1024 || png.size < 8 || !png.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) return@withLock
        val book = database.bookDao().get(doc.book.id) ?: return@withLock
        if(book.coverPath != null && File(book.coverPath).name != "cover.png") return@withLock
        val file = storage.bookDirectory(book.id).resolve("pdf-cover.png")
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(png); atomic.finishWrite(output) } catch(e:Exception) { atomic.failWrite(output); throw e }
        database.bookDao().updateCover(book.id,file.absolutePath)
    } }
    companion object {
        fun encode(p: PdfPosition) = JSONObject().put("pageIndex",p.pageIndex).put("left",p.left).put("top",p.top).put("scale",p.scale).toString()
        fun decode(raw: String) = runCatching { val p=JSONObject(raw); PdfPosition(p.optInt("pageIndex"),p.optDouble("left",0.0).toFloat(),p.optDouble("top",0.0).toFloat(),p.optString("scale","page-width")) }.getOrDefault(PdfPosition())
    }
}
