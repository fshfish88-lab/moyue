package com.moyue.reader.feature.bookshelf

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import com.moyue.reader.*
import com.moyue.reader.core.database.*
import com.moyue.reader.core.document.DocumentFormat
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

class V170Instrumentation : Instrumentation() {
    private var suite = "data"
    private var checks = 0
    private fun verify(ok: Boolean, label: String) { check(ok) { label }; checks++; sendStatus(1, Bundle().apply { putString("stream", "PASS $checks: $label") }) }
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite") ?: "data"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            lateinit var c: MoyueContainer
            runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
            if (suite == "management-snapshot") {
                val snapshot = JSONObject()
                for (table in listOf("book_management","custom_shelves","tags","book_shelves","book_tags")) {
                    val rows = org.json.JSONArray()
                    c.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor -> while(cursor.moveToNext()) {
                        val row = JSONObject(); for (i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i),if(cursor.isNull(i))JSONObject.NULL else cursor.getString(i)); rows.put(row)
                    } }
                    snapshot.put(table,rows)
                }
                snapshot.put("preferences",runBlocking { json(c.shelfPreferences.preferences.first()) })
                result.putString("managementSnapshot",snapshot.toString()); result.putString("stream","PASS management snapshot"); finish(-1,result); return
            }
            if (suite == "ui") ui(c) else runBlocking { withTimeout(120000) {
                when (suite) {
                    "preferences-seed" -> seedPreferences(c)
                    "preferences-check" -> checkPreferences(c)
                    else -> data(c)
                }
            } }
            result.putString("stream", "PASS v170 $suite checks=$checks"); finish(-1, result)
        } catch (e: Throwable) { result.putString("stream", "FAIL v170 $suite checks=$checks ${e.stackTraceToString()}"); finish(0, result) }
    }
    private fun json(p: ShelfPreferences): JSONObject = JSONObject().put("shelf",p.shelfId ?: JSONObject.NULL)
        .put("favorite",p.favoritesOnly).put("status",p.status?.name ?: JSONObject.NULL).put("sort",p.sort.name)
        .put("formats",org.json.JSONArray(p.formats.map { it.name })).put("authors",org.json.JSONArray(p.authors.toList()))
        .put("tags",org.json.JSONArray(p.tagIds.toList()))
    private fun prefs(p: JSONObject): ShelfPreferences {
        fun strings(key: String): Set<String> = p.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        return ShelfPreferences(if(p.isNull("shelf"))null else p.getLong("shelf"),p.getBoolean("favorite"),
            if(p.isNull("status"))null else ReadingStatus.valueOf(p.getString("status")),ShelfSort.valueOf(p.getString("sort")),
            strings("formats").map { DocumentFormat.valueOf(it) }.toSet(),strings("authors"),strings("tags").map { it.toLong() }.toSet())
    }
    private suspend fun seedPreferences(c: MoyueContainer) {
        val backup = c.applicationContext.cacheDir.resolve("qa-v170-prefs.json")
        check(!backup.exists()) { "previous preference fixture requires cleanup" }
        val original = c.shelfPreferences.preferences.first()
        val shelf = c.shelves.saveCategory(false,null,"V170重启书架-${System.nanoTime()}")
        val tag = c.shelves.saveCategory(true,null,"V170重启标签-${System.nanoTime()}")
        val expected = ShelfPreferences(shelf,true,ReadingStatus.WANT,ShelfSort.AUTHOR,setOf(DocumentFormat.TXT,DocumentFormat.EPUB),setOf("","作者"),setOf(tag))
        backup.writeText(JSONObject().put("original",json(original)).put("expected",json(expected)).toString())
        c.shelfPreferences.update { expected }
        verify(c.shelfPreferences.preferences.first()==expected,"all preference dimensions seeded for process restart")
    }
    private suspend fun checkPreferences(c: MoyueContainer) {
        val file = c.applicationContext.cacheDir.resolve("qa-v170-prefs.json")
        val fixture = JSONObject(file.readText())
        val expected = prefs(fixture.getJSONObject("expected"))
        try {
            verify(c.shelfPreferences.preferences.first()==expected,"fresh process restores collection favorite state sort formats authors and tags")
            verify(c.database.shelfDao().shelfExists(expected.shelfId!!)==1 && c.database.shelfDao().tagExists(expected.tagIds.single())==1,"classification catalog survives process restart")
        } finally {
            c.shelves.deleteCategory(false,expected.shelfId!!); c.shelves.deleteCategory(true,expected.tagIds.single())
            c.shelfPreferences.update { prefs(fixture.getJSONObject("original")) }; file.delete()
        }
    }
    private suspend fun data(c: MoyueContainer) {
        val saved = c.shelfPreferences.preferences.first()
        val name = "qa-v170-${System.nanoTime()}.db"
        val schema = JSONObject(context.assets.open("migration-fixtures/schema3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = targetContext.openOrCreateDatabase(name, 0, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val item = entities.getJSONObject(i); val table = item.getString("tableName")
                old.execSQL(item.getString("createSql").replace("\${TABLE_NAME}", table))
                item.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            for ((id, progress) in listOf(1 to 0.0, 2 to .5, 3 to 1.0)) old.execSQL("INSERT INTO books VALUES($id,'旧书$id','作者','TXT','/qa/old$id.txt','UTF-8',NULL,NULL,1,100,123,456,$progress)")
            old.execSQL("INSERT INTO chapters VALUES(9,2,'旧章节',0,0,100,NULL,NULL,NULL,NULL)")
            old.execSQL("INSERT INTO reading_progress VALUES(2,9,0,6,0.5,0.5,456)")
            old.execSQL("INSERT INTO document_states VALUES(3,'old',1,'hash','{}',789)")
            old.execSQL("INSERT INTO annotations VALUES(7,2,'NOTE','{}','摘录','旧笔记','yellow','旧章节','hash',123,456)")
            old.version = 3
        } finally { old.close() }
        val db = Room.databaseBuilder(targetContext, MoyueDatabase::class.java, name).addMigrations(MoyueDatabase.MIGRATION_3_4).build()
        val repo = ShelfRepository(db, c.shelfPreferences)
        try {
            verify(db.openHelper.readableDatabase.version == 4, "real schema3 migrates to Room validated schema4")
            verify(db.shelfDao().library().map { it.status } == ReadingStatus.entries, "old zero partial full progress initializes WANT READING FINISHED once")
            verify(db.readingProgressDao().get(2)?.charOffset == 6, "migration retains exact reading position")
            verify(db.documentDao().state(3)?.updatedAt == 789L, "migration retains document state")
            verify(db.annotationDao().forBook(2).first().single().note == "旧笔记", "migration retains annotation")
            val a = repo.saveCategory(false, null, "科幻")
            val b = repo.saveCategory(false, null, "待读")
            val t = repo.saveCategory(true, null, "长篇")
            val u = repo.saveCategory(true, null, "推荐")
            repo.categories(setOf(1,2), false, setOf(a), emptySet())
            repo.categories(setOf(1), false, setOf(b), emptySet())
            verify(db.shelfDao().library().first().shelves.map { it.id }.toSet() == setOf(a,b), "one book belongs to two shelves")
            repo.categories(setOf(1,2), true, setOf(t,u), emptySet())
            repo.favorite(setOf(1,2), true); repo.status(setOf(1,2), ReadingStatus.FINISHED)
            repo.status(setOf(1), ReadingStatus.WANT)
            db.bookDao().updateReading(1, 1f, 999)
            db.shelfDao().initializeMissing(); db.bookDao().get(1)!!.let { db.bookDao().update(it.copy(progress = .1f)) }
            val one = db.shelfDao().library().first()
            verify(one.status == ReadingStatus.WANT && one.management.single().favorite, "progress writes and initialization cannot overwrite manual status or favorite")
            repo.favorite(setOf(1,2), false)
            verify(db.shelfDao().library().map { it.status }.take(2) == listOf(ReadingStatus.WANT,ReadingStatus.FINISHED), "bulk favorite update preserves independent states")
            repo.favorite(setOf(1), true)
            repo.categories(setOf(1,2), false, setOf(b), setOf(a))
            verify(db.shelfDao().library().take(2).all { it.shelves.map { s -> s.id } == listOf(b) }, "bulk membership deltas add and remove only selected shelves")
            repo.categories(setOf(1), true, emptySet(), setOf(t))
            verify(db.shelfDao().library().first().tags.map { it.id } == listOf(u) && db.shelfDao().library()[1].tags.size == 2, "tag removal preserves other tags and unselected books")
            repo.saveCategory(false,b,"新书架"); repo.saveCategory(true,u,"新标签")
            verify(db.shelfDao().library().first().shelves.single().name == "新书架" && db.shelfDao().library().first().tags.single().name == "新标签", "rename keeps memberships")
            verify(runCatching { repo.saveCategory(false,null," 新书架 ") }.isFailure, "trimmed duplicate names rejected")
            verify(runCatching { repo.saveCategory(true,null," ") }.isFailure, "empty category rejected")
            val beforeInvalid = db.shelfDao().library()
            verify(runCatching { repo.categories(setOf(1,2),false,setOf(a,99999),setOf(b)) }.isFailure && db.shelfDao().library() == beforeInvalid, "invalid category bulk mutation rolls back all changes")
            verify(runCatching { repo.status(setOf(1,99999),ReadingStatus.READING) }.isFailure && db.shelfDao().library() == beforeInvalid, "missing book cannot partially change bulk state")
            repo.series(1,"  测试系列  ")
            verify(db.shelfDao().library().first().management.single().series == "测试系列", "series can be supplemented without altering status or favorite")
            val p = ShelfPreferences(b,true,ReadingStatus.WANT,ShelfSort.TITLE,setOf(DocumentFormat.TXT),setOf("作者"),setOf(u))
            c.shelfPreferences.update { p }
            verify(ShelfPreferencesRepository(targetContext).preferences.first() == p, "all filters and sort restore from DataStore")
            c.shelfPreferences.update { it.copy(sort = ShelfSort.PROGRESS) }
            c.shelfPreferences.update { it.copy(status = ReadingStatus.READING) }
            verify(c.shelfPreferences.preferences.first() == p.copy(sort=ShelfSort.PROGRESS,status=ReadingStatus.READING), "sequential preference patches retain other dimensions")
            verify(shelfBooks(db.shelfDao().library(),p).map { it.book.id } == listOf(1L), "real migrated relations support composed filter")
            repo.deleteCategory(false,b); repo.deleteCategory(true,u)
            verify(db.bookDao().count() == 3 && db.shelfDao().library().first().shelves.isEmpty(), "deleting shelf keeps every book")
            verify(db.shelfDao().library()[1].tags.single().id == t && db.annotationDao().forBook(2).first().single().note == "旧笔记", "deleting tag keeps books other tags and annotations")
            verify(c.shelfPreferences.preferences.first().shelfId == null && u !in c.shelfPreferences.preferences.first().tagIds, "deleted categories removed from saved filters")
            db.bookDao().delete(db.bookDao().get(2)!!)
            verify(db.shelfDao().library().none { it.book.id == 2L } && db.readingProgressDao().get(2) == null && db.annotationDao().forBook(2).first().isEmpty(), "book deletion cascades owned data only")
            verify(db.shelfDao().library().first().management.single().favorite && db.shelfDao().library().first().status == ReadingStatus.WANT, "other books remain unchanged after delete")
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { verify(!it.moveToNext(), "no dangling foreign keys") }
        } finally { c.shelfPreferences.update { saved }; db.close(); targetContext.deleteDatabase(name) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { list += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        uiAutomation.rootInActiveWindow?.let(::walk); return list
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(100) { nodes().lastOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }; SystemClock.sleep(100) }
        error("UI missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun tap(label: String, hold: Boolean = false) {
        val node = waitFor(label)
        val rect = Rect(); node.getBoundsInScreen(rect); check(!rect.isEmpty)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0).let {
                it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(it,true)); it.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(if (hold) 800 else 60)
        }
        SystemClock.sleep(600)
    }
    private fun screenshot(name: String) { val bitmap = requireNotNull(uiAutomation.takeScreenshot()); targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
    private fun ui(c: MoyueContainer) {
        val saved = runBlocking { c.shelfPreferences.preferences.first() }
        val ids = mutableListOf<Long>()
        var shelf: Long? = null; var tag: Long? = null
        try {
            runBlocking {
                c.shelfPreferences.update { ShelfPreferences(sort=ShelfSort.IMPORTED) }
                for (i in 1..3) {
                    val file=targetContext.cacheDir.resolve("V170验收$i.txt").apply { writeText("第一章 书架测试\n\n阅读内容。\n".repeat(15)) }
                    val imported=c.importService.importDocument(android.net.Uri.fromFile(file),file.name) as ImportState.Completed
                    ids += imported.bookId
                    c.database.bookDao().get(imported.bookId)!!.let { c.database.bookDao().update(it.copy(title="V170验收$i")) }
                    file.delete()
                }
                shelf=c.shelves.saveCategory(false,null,"V170 UI书架"); tag=c.shelves.saveCategory(true,null,"V170 UI标签")
            }
            startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("V170验收3"); verify(true,"cover grid and management controls visible")
            tap("V170验收3",true); waitFor("加入书架"); waitFor("标签管理"); waitFor("系列信息"); verify(true,"long press exposes independent management dimensions")
            tap("收藏书籍"); waitFor("墨阅")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id==ids[2] }.management.single().favorite },"long press favorite persisted")
            tap("V170验收3",true); tap("阅读状态 · 想读"); tap("读完"); waitFor("墨阅")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id==ids[2] }.status==ReadingStatus.FINISHED },"manual status persisted from UI")
            tap("V170验收3",true); tap("加入书架"); tap("V170 UI书架"); tap("应用"); waitFor("墨阅")
            verify(runBlocking { c.database.shelfDao().library().first { it.book.id==ids[2] }.shelves.single().id==shelf },"single shelf assignment persisted")
            tap("多选"); waitFor("已选 0 本"); tap("V170验收3"); waitFor("已选 1 本"); tap("V170验收2"); waitFor("已选 2 本"); screenshot("v170-multiselect.png")
            tap("标签"); tap("V170 UI标签"); tap("应用"); waitFor("已选 2 本")
            verify(runBlocking { c.database.shelfDao().library().filter { it.book.id in setOf(ids[1],ids[2]) }.all { it.tags.single().id==tag } },"bulk tags apply only to selected books")
            tap("状态"); tap("在读"); waitFor("已选 2 本")
            verify(runBlocking { c.database.shelfDao().library().filter { it.book.id in setOf(ids[1],ids[2]) }.all { it.status==ReadingStatus.READING } },"bulk status applies to selected books")
            tap("完成"); tap("筛选"); waitFor("格式"); verify(true,"formats moved into filter panel"); tap("取消")
            tap("导入时间 ▾"); tap("书名"); waitFor("书名 ▾")
            tap("摘录"); waitFor("搜索摘录、笔记或书名"); tap("返回"); waitFor("书名 ▾")
            verify(runBlocking { c.shelfPreferences.preferences.first().sort==ShelfSort.TITLE },"sort survives navigation away and return")
            runBlocking { c.shelfPreferences.update { it.copy(sort=ShelfSort.IMPORTED) } }; waitFor("导入时间 ▾")
            screenshot("v170-bookshelf.png")
            tap("多选"); tap("V170验收3"); tap("V170验收2"); waitFor("已选 2 本")
            tap("删除"); waitFor("删除 2 本书籍？"); tap("取消"); waitFor("已选 2 本")
            verify(runBlocking { ids.all { c.database.bookDao().get(it)!=null } },"cancel bulk deletion retains all books")
            tap("删除"); tap("确认删除")
            for (attempt in 0 until 100) { if (runBlocking { c.database.bookDao().get(ids[1])==null }) break; SystemClock.sleep(100) }
            verify(runBlocking { c.database.bookDao().get(ids[1])==null && c.database.bookDao().get(ids[2])==null && c.database.bookDao().get(ids[0])!=null },"confirmed bulk delete removes only selected books")
            verify(!c.storage.bookDirectory(ids[1]).exists() && !c.storage.bookDirectory(ids[2]).exists(),"bulk delete removes owned file copies")
        } finally { runBlocking {
            for(id in ids) { c.database.bookDao().get(id)?.let { c.database.bookDao().delete(it) }; c.storage.bookDirectory(id).deleteRecursively() }
            shelf?.let { c.shelves.deleteCategory(false,it) }; tag?.let { c.shelves.deleteCategory(true,it) }
            c.database.searchDao().prune(); c.shelfPreferences.update { saved }
        } }
    }
}
