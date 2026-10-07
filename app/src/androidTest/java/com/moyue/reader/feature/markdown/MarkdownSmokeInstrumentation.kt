package com.moyue.reader.feature.markdown

import android.app.Instrumentation
import android.os.Bundle
import android.net.Uri
import com.moyue.reader.MoyueApplication
import com.moyue.reader.core.model.SourceType
import com.moyue.reader.feature.importbook.ImportState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

open class MarkdownSmokeInstrumentation : com.moyue.reader.feature.reader.LayoutSmokeInstrumentation() {
    private var suite: String? = null
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite"); super.onCreate(arguments) }
    override fun onStart() {
        if (suite == "layout") { super.onStart(); return }
        val result = Bundle(); var checks = 0
        fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
        try {
            runBlocking {
                lateinit var c: com.moyue.reader.MoyueContainer
                runOnMainSync { c = (targetContext.applicationContext as MoyueApplication).container }
                val before = c.database.bookDao().count()
                val imported = c.importService.newMarkdown() as ImportState.Completed
                val id = imported.bookId
                val original = c.markdown.open(id)
                verify(original.book.sourceType == SourceType.MARKDOWN && original.text.isEmpty(), "empty new document")
                val gate = MarkdownRevisionGate()
                val text = "# Markdown 验证\n\n中文正文 **加粗**。\n\n## 清单\n\n- [x] 完成\n- [ ] 待办\n\n|名称|值|\n|---|---|\n|中文|123|\n\n```md\n# 代码中的标题\n```\n"
                verify(c.markdown.save(id, text, 1, gate), "save")
                verify(!c.markdown.save(id, "旧版", 0, gate), "stale revision rejected")
                verify(runCatching { c.markdown.save(id, "a".repeat(1024 * 1024 + 1), 2, gate) }.isFailure, "oversize save rejected")
                verify(c.markdown.open(id).text == text, "failed save retains source")
                verify(c.markdown.save(id, text, 2, gate), "same revision retries after failed save")
                val opened = c.markdown.open(id)
                verify(opened.text == text && opened.recovery == null, "saved source reopen")
                c.markdown.checkpoint(id, text + "未提交草稿", 3)
                verify(c.markdown.open(id).recovery?.optString("text") == text + "未提交草稿", "draft recovery")
                c.markdown.discardDraft(id)
                verify(c.markdown.open(id).text == text && c.markdown.open(id).recovery == null, "discard does not affect source")
                c.markdown.savePosition(id, JSONObject().put("heading", "清单").put("headingIndex", 1).put("offset", 10).put("ratio", .4))
                verify(c.markdown.open(id).state.optString("heading") == "清单", "position persists")
                val root = c.storage.bookDirectory(id)
                root.resolve("assets").mkdirs(); root.resolve("assets/test.png").writeBytes(byteArrayOf(1,2,3))
                val zip = targetContext.cacheDir.resolve("md-test-export.zip")
                c.markdown.export(id, Uri.fromFile(zip), true)
                ZipFile(zip).use { z ->
                    verify(z.getEntry("未命名.md") != null && z.getEntry("assets/test.png") != null, "bundle contains source and assets")
                    verify(z.getInputStream(z.getEntry("未命名.md")).bufferedReader().readText() == text, "export source exact")
                }
                val md = targetContext.cacheDir.resolve("md-test-export.md")
                c.markdown.export(id, Uri.fromFile(md), false)
                verify(md.readBytes().contentEquals(File(opened.book.sourcePath).readBytes()), "plain export byte exact")
                val cache = c.storage.epubCache(id).parentFile!!
                cache.deleteRecursively()
                verify(c.markdown.open(id).text == text && root.resolve("assets/test.png").exists(), "cache clearing protects persistent data")
                c.markdown.rename(id, "Markdown 验证")
                verify(c.database.bookDao().get(id)?.title == "Markdown 验证", "rename")
                val utf = targetContext.cacheDir.resolve("original.md")
                utf.writeBytes(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()) + "# 原文\r\n\r\n- [ ] 中文\r\n".toByteArray())
                val second = c.importService.importDocument(Uri.fromFile(utf), "原文.md") as ImportState.Completed
                val copied = c.database.bookDao().get(second.bookId)!!
                verify(File(copied.sourcePath).readBytes().contentEquals(utf.readBytes()), "import keeps original BOM and CRLF")
                val bad = targetContext.cacheDir.resolve("bad.md"); bad.writeBytes(byteArrayOf(0xff.toByte()))
                verify(c.importService.importDocument(Uri.fromFile(bad), "bad.md") !is ImportState.Completed, "invalid UTF8 rejected")
                verify(c.database.bookDao().count() == before + 2, "failed import leaves no ghost row")
                c.database.bookDao().delete(copied); c.storage.bookDirectory(second.bookId).deleteRecursively()
                result.putLong("fixtureBookId", id)
            }
            result.putString("stream", "PASS markdown checks=$checks"); finish(-1, result)
        } catch (error: Throwable) { result.putString("stream", "FAIL ${error.stackTraceToString()}"); finish(0, result) }
    }
}
