package com.moyue.reader.feature.importbook

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyue.reader.MoyueContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SharedWebUrl(val id: String, val url: String)

/** Holds the import job across Activity recreation and serializes repeated shares. */
class ShareReceiverViewModel(private val container: MoyueContainer) : ViewModel() {
    var webUrl by mutableStateOf<SharedWebUrl?>(null)
        private set
    var importState by mutableStateOf<ImportState?>(null)
        private set
    private val requests = Channel<List<Uri>>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (uris in requests) {
                val failures = mutableListOf<String>()
                var lastCompleted: ImportState.Completed? = null
                var completed = 0
                for (uri in uris) {
                    importState = ImportState.Running(UUID.randomUUID().toString(), ImportStage.WAITING, 0f)
                    val name = withContext(Dispatchers.IO) { sharedFileName(uri) }
                    when (val result = container.importService.importDocument(uri, name) { importState = it }) {
                        is ImportState.Completed -> { lastCompleted = result; completed++ }
                        is ImportState.RecoverableError -> failures += "${name ?: "文件"}：${result.message}"
                        is ImportState.FatalError -> failures += "${name ?: "文件"}：${result.message}"
                        else -> failures += "${name ?: "文件"}：导入已取消"
                    }
                }
                importState = if (failures.isEmpty()) lastCompleted else ImportState.RecoverableError(
                    UUID.randomUUID().toString(),
                    "已导入 $completed/${uris.size} 个文件。\n" + failures.joinToString("\n"),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val streams = runCatching {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }.getOrDefault(emptyList())
        // ClipData is used by senders which omit EXTRA_STREAM; avoid importing both copies.
        val uris = streams.ifEmpty {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }.distinct()
        if (uris.isNotEmpty()) {
            webUrl = null
            // A sender may grant content URIs, but must not point into our private filesystem.
            if (uris.any { it.scheme != "content" }) {
                importState = ImportState.RecoverableError("share", "无法读取分享的文件，请从文件管理器重新分享")
            } else requests.trySend(uris)
        } else {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (text.startsWith("http://") || text.startsWith("https://")) {
                if (importState !is ImportState.Running) importState = null
                webUrl = SharedWebUrl(UUID.randomUUID().toString(), text)
            } else importState = ImportState.RecoverableError("share", "未收到可读取的文件或网页链接，请重新分享")
        }
    }

    fun dismissImport() { importState = null }
    fun consumeWebUrl(id: String) { if (webUrl?.id == id) webUrl = null }

    private fun sharedFileName(uri: Uri): String? {
        val resolver = container.applicationContext.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
        if (!name.isNullOrBlank() && name.contains('.')) return name
        val extension = when (runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.lowercase()) {
            "text/plain" -> "txt"
            "application/epub+zip" -> "epub"
            "text/markdown", "text/x-markdown" -> "md"
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "application/zip", "application/x-zip-compressed" -> "zip"
            else -> return name
        }
        return "${name?.takeIf { it.isNotBlank() } ?: "分享文件"}.$extension"
    }
}
