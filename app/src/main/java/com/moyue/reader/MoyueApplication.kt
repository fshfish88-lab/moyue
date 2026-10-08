package com.moyue.reader

import android.app.Application
import com.moyue.reader.core.database.MoyueDatabase
import com.moyue.reader.core.settings.ReaderPreferencesRepository
import com.moyue.reader.core.storage.AtomicImportCoordinator
import com.moyue.reader.core.storage.BookStorage
import com.moyue.reader.core.storage.RoomAtomicImportStore
import com.moyue.reader.core.ui.GeneratedCoverFileWriter
import com.moyue.reader.feature.importbook.ImportService

class MoyueApplication : Application() {
    lateinit var container: MoyueContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = MoyueContainer(this)
        container.importCoordinator.cleanupAbandonedImports()
    }
}

class MoyueContainer(val applicationContext: Application) {
    val updates = com.moyue.reader.feature.update.AppUpdateManager(applicationContext)
    val database = MoyueDatabase.get(applicationContext)
    val storage = BookStorage(applicationContext.filesDir, applicationContext.cacheDir).also(BookStorage::ensureRoots)
    val preferences = ReaderPreferencesRepository(applicationContext)
    val importCoordinator = AtomicImportCoordinator(
        storage,
        RoomAtomicImportStore(database, storage, GeneratedCoverFileWriter()),
    )
    val webPages = com.moyue.reader.feature.importbook.BrowserWebPageSource(applicationContext)
    val importService = ImportService(applicationContext, importCoordinator, webPages)
    val markdown = com.moyue.reader.feature.markdown.MarkdownRepository(applicationContext, database, storage)
    val visuals = com.moyue.reader.feature.image.VisualRepository(database, storage)
    val documents = com.moyue.reader.feature.pdf.PdfRepository(database, storage)
    val documentWrites = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
}
