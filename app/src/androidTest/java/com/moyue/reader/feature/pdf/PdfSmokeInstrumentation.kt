package com.moyue.reader.feature.pdf

import android.net.Uri
import android.os.Bundle
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.document.PdfPosition
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

class PdfSmokeInstrumentation : com.moyue.reader.feature.markdown.MarkdownSmokeInstrumentation() {
    private var requestedSuite: String? = null
    private var cleanupIds: String? = null
    override fun onCreate(arguments: Bundle?) {requestedSuite=arguments?.getString("suite"); cleanupIds=arguments?.getString("ids"); super.onCreate(arguments)}
    override fun onStart() {
        if(requestedSuite in setOf("markdown","layout")) {super.onStart(); return}
        val result=Bundle(); var checks=0
        fun verify(value:Boolean,label:String) {check(value) {label}; checks++}
        try {
            runBlocking {
                lateinit var c: com.moyue.reader.MoyueContainer
                runOnMainSync {c=(targetContext.applicationContext as MoyueApplication).container}
                if(requestedSuite=="cleanup") {
                    for(id in cleanupIds.orEmpty().split(',').mapNotNull(String::toLongOrNull)) {
                        require(id>3)
                        val book=c.database.bookDao().get(id) ?: continue
                        require(book.sourceType==SourceType.DOCUMENT)
                        val bytes=File(book.sourcePath).readBytes()
                        require(listOf("pdf-basic.pdf","pdf-scan.pdf","pdf-1000.pdf","pdf-password.pdf").any {name -> context.assets.open("pdf-fixtures/$name").use {it.readBytes()}.contentEquals(bytes)}) {"only owned QA fixtures may be removed"}
                        c.database.bookDao().delete(book); c.storage.bookDirectory(id).deleteRecursively()
                    }
                    result.putString("stream","PASS owned PDF fixture cleanup"); finish(-1,result); return@runBlocking
                }
                val original=c.database.bookDao().observeAll().first()
                val originalBytes=original.associate {it.id to File(it.sourcePath).readBytes()}
                val ids=JSONObject()
                for((name,count) in listOf("pdf-basic.pdf" to 12,"pdf-scan.pdf" to 1,"pdf-1000.pdf" to 1000,"pdf-password.pdf" to -1)) {
                    val input=targetContext.cacheDir.resolve("pdf-test/$name"); input.parentFile!!.mkdirs()
                    context.assets.open("pdf-fixtures/$name").use {source->input.outputStream().use {source.copyTo(it)}}
                    val imported=c.importService.importDocument(Uri.fromFile(input),name)
                    verify(imported is ImportState.Completed,"$name imported: $imported")
                    val id=(imported as ImportState.Completed).bookId; ids.put(name,id)
                    val doc=c.documents.open(id)
                    verify(doc.book.sourceType==SourceType.DOCUMENT,"document source type")
                    verify(c.database.chapterDao().forBook(id).isEmpty(),"no fake chapters")
                    verify(doc.source.readBytes().contentEquals(input.readBytes()),"source byte exact")
                    verify(if(count<0) doc.metadata.locked && doc.metadata.pageCount==null else doc.metadata.pageCount==count,"pages/password metadata")
                }
                val goodCount=c.database.bookDao().count()
                for(name in listOf("pdf-broken.pdf","pdf-fake.pdf")) {
                    val input=targetContext.cacheDir.resolve("pdf-test/$name")
                    context.assets.open("pdf-fixtures/$name").use {source->input.outputStream().use {source.copyTo(it)}}
                    verify(c.importService.importDocument(Uri.fromFile(input),name) !is ImportState.Completed,"reject $name")
                    verify(c.database.bookDao().count()==goodCount,"no ghost row")
                }
                val doc=c.documents.open(ids.getLong("pdf-basic.pdf"))
                c.documents.save(doc,PdfPosition(7,10f,200f,"1.5"),12)
                c.documents.save(doc,PdfPosition(2),12,eventTime=1L)
                verify(c.documents.open(doc.book.id).position==PdfPosition(7,10f,200f,"1.5"),"page coordinates persist")
                verify(c.database.readingProgressDao().get(doc.book.id)==null,"chapter progress untouched")
                c.documents.bookmark(doc,PdfPosition(7,0f,200f))
                val marks=c.database.documentDao().bookmarks(doc.book.id).first()
                verify(marks.size==1 && PdfRepository.decode(marks[0].positionJson).pageIndex==7,"bookmark persists")
                c.database.documentDao().deleteBookmark(marks[0].id)
                verify(c.database.documentDao().bookmarks(doc.book.id).first().isEmpty(),"bookmark deletion")
                c.applicationContext.cacheDir.resolve("books").deleteRecursively(); c.storage.ensureRoots()
                verify(c.documents.open(doc.book.id).position.pageIndex==7,"clear cache preserves PDF progress")
                verify(doc.source.exists(),"clear cache preserves source")
                val host=PdfResourceHost(targetContext,doc.source)
                verify(host.response(Uri.parse("https://example.com/file.pdf")).statusCode==403,"remote resource blocked")
                verify(host.response(Uri.parse("$PDF_ORIGIN/file/data/secret")).statusCode==403,"arbitrary local path blocked")
                verify(host.response(Uri.parse("$PDF_ORIGIN/assets/pdf/../secret")).statusCode==403,"path traversal blocked")
                val ranged=host.response(Uri.parse(PDF_SOURCE),mapOf("Range" to "bytes=0-7"))
                verify(ranged.statusCode==206 && ranged.data.use {it.readBytes()}.contentEquals(doc.source.readBytes().take(8).toByteArray()),"PDF range response bounded")
                val before=c.preferences.preferences.first()
                c.preferences.update {it.copy(pdfPageMode="single",pdfFit="page-fit",pdfInvert=true)}
                val after=c.preferences.preferences.first()
                verify(after.pdfPageMode=="single" && after.fontSizeSp==before.fontSizeSp && after.pageMode==before.pageMode,"PDF settings independent of books")
                c.preferences.update {before}
                for(book in original) {
                    verify(c.database.bookDao().get(book.id)==book,"old book record unchanged ${book.id}")
                    verify(File(book.sourcePath).readBytes().contentEquals(originalBytes[book.id]),"old source unchanged ${book.id}")
                }
                val db=c.database.openHelper.readableDatabase
                verify(db.version==2,"non-destructive migration reaches schema2")
                result.putString("fixtures",ids.toString())
            }
            result.putString("stream","PASS pdf checks=$checks"); finish(-1,result)
        } catch(error:Throwable) {result.putString("stream","FAIL ${error.stackTraceToString()}"); finish(0,result)}
    }
}
