package com.moyue.reader.feature.reader

import android.app.Instrumentation
import android.os.Bundle
import com.moyue.reader.MoyueApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only core data snapshot with source hashes and raw preference backups. */
class OptimizationSnapshotInstrumentation : Instrumentation() {
    private var label = "snapshot"
    override fun onCreate(arguments: Bundle?) {
        label = arguments?.getString("label") ?: label
        super.onCreate(arguments); start()
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    override fun onStart() {
        val result = Bundle()
        try {
            lateinit var database: androidx.sqlite.db.SupportSQLiteDatabase
            runOnMainSync { database = (targetContext.applicationContext as MoyueApplication).container.database.openHelper.readableDatabase }
            val root = JSONObject()
            val tables = database.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }.filterNot { it.startsWith("search_") || it in setOf("room_master_table", "android_metadata", "sqlite_sequence") }
            for (table in tables) {
                val rows = database.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(JSONObject().apply {
                            for (i in 0 until cursor.columnCount) put(cursor.getColumnName(i), if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i))
                        }.toString())
                    }
                }.sorted()
                root.put(table, JSONArray(rows.map(::JSONObject)))
            }
            val sources = JSONObject()
            database.query("SELECT id, sourcePath FROM books ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) sources.put(cursor.getString(0), hash(File(cursor.getString(1))))
            }
            root.put("sourceHashes", sources)
            val preferences = JSONObject()
            File(targetContext.filesDir, "datastore").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { preferences.put(it.name, hash(it)) }
            root.put("preferenceHashes", preferences)
            File(targetContext.filesDir, "datastore/reader_preferences.preferences_pb").copyTo(
                File(targetContext.getExternalFilesDir(null), "optimization-$label-reader.pb"), overwrite = true)
            File(targetContext.getExternalFilesDir(null), "optimization-$label.json").writeText(root.toString(2))
            result.putString("stream", "PASS read-only snapshot tables=${tables.size} sources=${sources.length()}")
            finish(-1, result)
        } catch (error: Throwable) { result.putString("stream", "FAIL ${error.stackTraceToString()}"); finish(0, result) }
    }
}
