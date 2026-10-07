package com.moyue.reader.feature.pdf

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Android-only APIs: also runs against the previous signed Release before upgrading it. */
class PdfSnapshotInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments); start()}
    override fun onStart() {
        val result=Bundle()
        try {
            val value=JSONObject()
            SQLiteDatabase.openDatabase(targetContext.getDatabasePath("moyue.db").absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                value.put("schema",db.version)
                for(table in listOf("books","chapters","reading_progress","document_metadata","document_states","document_bookmarks")) {
                    val exists=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use {it.moveToFirst()}
                    if(!exists)continue
                    val rows=JSONArray()
                    db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use { cursor ->
                        while(cursor.moveToNext()) {val row=JSONObject(); cursor.columnNames.forEachIndexed {i,name -> row.put(name,if(cursor.isNull(i)) JSONObject.NULL else cursor.getString(i))}; rows.put(row)}
                    }
                    value.put(table,rows)
                }
            }
            val files=JSONObject()
            val base=targetContext.filesDir.resolve("books")
            base.walkTopDown().filter {it.isFile}.forEach {f ->
                val digest=MessageDigest.getInstance("SHA-256"); f.inputStream().use {stream -> val buffer=ByteArray(65536); while(true) {val n=stream.read(buffer); if(n<0) break; digest.update(buffer,0,n)}}
                files.put(f.relativeTo(base).invariantSeparatorsPath,digest.digest().joinToString("") {"%02x".format(it)})
            }
            value.put("files",files); result.putString("snapshot",value.toString()); result.putString("stream","PASS snapshot"); finish(-1,result)
        } catch(error:Throwable) {result.putString("stream",error.stackTraceToString()); finish(0,result)}
    }
}
