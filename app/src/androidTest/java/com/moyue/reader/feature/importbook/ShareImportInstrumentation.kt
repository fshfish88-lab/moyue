package com.moyue.reader.feature.importbook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.moyue.reader.MainActivity
import com.moyue.reader.MoyueApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ShareImportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun report(value: String) = sendStatus(1, Bundle().apply { putString("stream", value) })
    private fun await(condition: () -> Boolean) {
        repeat(300) { if (condition()) return; SystemClock.sleep(100) }
        error("Timed out waiting for shared import")
    }
    override fun onStart() {
        val result = Bundle()
        var checks = 0
        fun verify(ok: Boolean, label: String) { check(ok) { label }; checks++; report("PASS $checks: $label") }
        lateinit var container: com.moyue.reader.MoyueContainer
        runOnMainSync { container = (targetContext.applicationContext as MoyueApplication).container }
        fun books() = runBlocking { container.database.bookDao().observeAll().first() }
        fun hash(path: String) = MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).toList()
        val original = books()
        val originalFiles = original.filter { File(it.sourcePath).isFile }.associate { it.id to hash(it.sourcePath) }
        val imported = mutableListOf<Long>()
        var activity: MainActivity? = null
        var created = 0
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) { if (a is MainActivity) { activity = a; created++ } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        val app = targetContext.applicationContext as Application
        runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        fun uri(extension: String) = Uri.parse("content://com.moyue.reader.test.sharefiles/${UUID.randomUUID()}.$extension")
        fun share(uris: List<Uri>, mime: String, clipOnly: Boolean = false): Intent = Intent(
            if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
        ).apply {
            component = ComponentName(targetContext, MainActivity::class.java)
            type = mime
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("file", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            if (!clipOnly) {
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        fun receiver(): ShareReceiverViewModel {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                return ViewModelProvider(requireNotNull(activity))[ShareReceiverViewModel::class.java]
            }
            lateinit var vm: ShareReceiverViewModel
            runOnMainSync { vm = ViewModelProvider(requireNotNull(activity))[ShareReceiverViewModel::class.java] }
            return vm
        }
        fun send(intent: Intent) {
            context.startActivity(Intent(intent).apply {
                component = ComponentName(context, ShareSenderActivity::class.java)
                putExtra("share_flags", intent.flags)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        fun import(uris: List<Uri>, mime: String, clipOnly: Boolean = false, cold: Boolean = false) {
            val before = books().map { it.id }.toSet()
            activity?.let { runOnMainSync { receiver().dismissImport() } }
            send(share(uris, mime, clipOnly))
            await { activity != null }
            await { receiver().importState is ImportState.Completed || receiver().importState is ImportState.RecoverableError }
            val added = books().filter { it.id !in before }
            imported += added.map { it.id }
            verify(receiver().importState is ImportState.Completed && added.size == uris.size,
                "${if (cold) "cold" else "warm"} ${uris.size} file(s) $mime clipOnly=$clipOnly: ${receiver().importState}")
            for (book in added) verify(File(book.sourcePath).isFile && File(book.sourcePath).length() > 0, "saved owned copy ${book.id}")
        }
        try {
            @Suppress("DEPRECATION")
            for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) for (mime in listOf(
                "text/plain", "application/epub+zip", "application/pdf", "text/markdown", "image/png", "application/zip", "application/octet-stream",
            )) {
                val targets = targetContext.packageManager.queryIntentActivities(Intent(action).setType(mime), PackageManager.MATCH_DEFAULT_ONLY)
                verify(targets.any { it.activityInfo.packageName == "com.moyue.reader" }, "chooser resolves $action $mime")
            }
            import(listOf(uri("txt")), "text/plain", cold = true)
            val firstReceiver = receiver()
            import(listOf(uri("epub")), "application/octet-stream")
            import(listOf(uri("pdf")), "application/pdf")
            import(listOf(uri("md")), "text/markdown", clipOnly = true)
            import(listOf(Uri.parse("content://com.moyue.reader.test.sharefiles/${UUID.randomUUID()}")), "text/plain")
            import(listOf(uri("png")), "image/png")
            import(listOf(uri("cbz")), "application/octet-stream")
            import(listOf(uri("zip")), "application/zip")
            import(listOf(uri("txt"), uri("md")), "application/octet-stream")
            verify(created == 1 && receiver() === firstReceiver, "warm shares reuse Activity and receiver")
            val beforeRotation = books().size
            runOnMainSync { requireNotNull(activity).recreate() }
            await { created == 2 }
            SystemClock.sleep(800)
            verify(receiver() === firstReceiver && books().size == beforeRotation, "Activity recreation does not duplicate shared imports")
            val beforeFailure = books().size
            runOnMainSync { receiver().dismissImport() }
            send(share(listOf(uri("docx")), "application/octet-stream"))
            await { receiver().importState is ImportState.RecoverableError }
            verify(books().size == beforeFailure, "unsupported document reports error without residual shelf row")
            runOnMainSync { receiver().dismissImport() }
            send(share(listOf(uri("txt")), "text/plain").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK; clipData = null
            })
            await { receiver().importState is ImportState.RecoverableError }
            verify(books().size == beforeFailure, "missing URI grant reports error without residual shelf row")
            runOnMainSync { receiver().dismissImport() }
            val beforePartial = books().map { it.id }.toSet()
            send(share(listOf(uri("docx"), uri("txt")), "application/octet-stream"))
            await { receiver().importState is ImportState.RecoverableError }
            val partial = books().filter { it.id !in beforePartial }
            imported += partial.map { it.id }
            verify(partial.size == 1 && (receiver().importState as ImportState.RecoverableError).message.contains("1/2"),
                "batch continues after an unsupported file and reports partial success")
            runOnMainSync { receiver().dismissImport() }
            send(Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(targetContext, MainActivity::class.java); type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(Intent.EXTRA_TEXT, "https://example.com/share-regression")
            })
            await { uiAutomation.rootInActiveWindow?.let { root ->
                fun contains(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                    n.text?.toString()?.contains("https://example.com/share-regression") == true || (0 until n.childCount).any { n.getChild(it)?.let(::contains) == true }
                contains(root)
            } == true }
            verify(true, "warm webpage share still fills the existing import URL field")
            verify(books().filter { it.id !in imported } == original, "existing shelf rows and reading progress unchanged")
            verify(originalFiles.all { (id, digest) -> hash(original.first { it.id == id }.sourcePath) == digest }, "existing source bytes unchanged")
            result.putString("stream", "PASS $checks share checks; fixtures=$imported")
        } catch (error: Throwable) { result.putString("stream", "FAIL after $checks checks: ${android.util.Log.getStackTraceString(error)}")
        } finally {
            runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks); activity?.finish() }
            runBlocking {
                for (id in imported) container.database.bookDao().get(id)?.let {
                    container.database.bookDao().delete(it)
                    container.storage.bookDirectory(id).deleteRecursively()
                    container.storage.epubCache(id).parentFile?.deleteRecursively()
                }
                container.database.searchDao().prune()
            }
        }
        finish(if (result.getString("stream")?.startsWith("PASS") == true) -1 else 0, result)
    }
}
