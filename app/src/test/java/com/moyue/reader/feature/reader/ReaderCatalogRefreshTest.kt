package com.moyue.reader.feature.reader

import com.moyue.reader.core.model.ReaderPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderCatalogRefreshTest {
    @Test fun insertedEarlierChaptersKeepTheCurrentChapterAndCharacterPosition()=runBlocking {
        val original=FakeReaderDataSource(2)
        val rows=original.chapters(1)
        val source=object:ReaderDataSource by original {
            override suspend fun refreshCatalog(bookId:Long)=listOf(rows[0].copy(id=99,chapterIndex=0))+rows.map {it.copy(chapterIndex=it.chapterIndex+1)}
            override suspend fun catalogMessage(bookId:Long)="已识别 3 章"
        }
        val model=ReaderViewModel(ReaderRepository(source));model.open(1)
        try{model.updatePosition(ReaderPosition(0,8),.2f)}catch(_:IllegalStateException){}
        model.refreshCatalog()
        val state=requireNotNull(model.state.value)
        assertEquals(3,state.chapters.size);assertEquals(rows[0].id,state.chapter.id);assertEquals(1,state.chapter.index)
        assertEquals(ReaderPosition(0,8),state.position);assertFalse(state.catalogRefreshing)
        model.flushProgressOnBack();assertEquals(rows[0].id,original.storedProgress?.chapterId);assertEquals(8,original.storedProgress?.charOffset)
    }
    @Test fun refreshFailurePreservesTheOpenChapterAndStopsTheLoadingState()=runBlocking {
        val original=FakeReaderDataSource(2)
        val source=object:ReaderDataSource by original {override suspend fun refreshCatalog(bookId:Long):List<com.moyue.reader.core.database.ChapterEntity> = throw java.io.IOException("offline")}
        val model=ReaderViewModel(ReaderRepository(source));model.open(1)
        val before=requireNotNull(model.state.value);model.refreshCatalog();val after=requireNotNull(model.state.value)
        assertEquals(before.chapter,after.chapter);assertEquals(before.chapters,after.chapters);assertEquals(before.position,after.position)
        assertFalse(after.catalogRefreshing);assertTrue(after.catalogMessage!!.contains("offline"))
    }
}
