package com.moyue.reader.core.document

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComicArchiveTest {
    private fun zip(vararg names:String):File {
        val file=Files.createTempFile("comic-", ".cbz").toFile()
        ZipOutputStream(file.outputStream()).use {z->names.forEach {n->z.putNextEntry(ZipEntry(n));z.write(byteArrayOf(1,2,3));z.closeEntry()}}
        return file
    }
    private fun reject(vararg names:String) {
        val file=zip(*names)
        try {runCatching {ComicArchive.scan(file, {ImageSize(100,200)})}.onSuccess {fail("invalid archive accepted")}}finally {file.delete()}
    }
    @Test fun numericOrderAndNestedPaths() {
        val f=zip("chapter10/1.png","chapter2/10.jpg","chapter2/2.jpg","chapter2/1.jpg","__MACOSX/._1.jpg","Thumbs.db")
        try {assertEquals(listOf("chapter2/1.jpg","chapter2/2.jpg","chapter2/10.jpg","chapter10/1.png"),ComicArchive.scan(f, {ImageSize(100,200)}).map {it.name})}finally {f.delete()}
    }
    @Test fun numberRunsDoNotOverflow() {assertTrue(NaturalPageOrder.compare("9999999999999999999.png","10000000000000000000.png")<0)}
    @Test fun leadingZerosHaveStableOrder() {assertEquals(listOf("001.png","1.png","2.png","10.png"),listOf("10.png","1.png","001.png","2.png").sortedWith(NaturalPageOrder))}
    @Test fun traversalRejected() {reject("../1.jpg")}
    @Test fun absolutePathRejected() {reject("/1.jpg")}
    @Test fun windowsPathRejected() {reject("C:\\1.jpg")}
    @Test fun emptyPathComponentRejected() {reject("dir//1.jpg")}
    @Test fun duplicateCaseRejected() {reject("1.jpg","1.JPG")}
    @Test fun duplicateUnicodeRejected() {reject("é.jpg","e\u0301.jpg")}
    @Test fun officeContainerIsNotComic() {reject("[Content_Types].xml","1.jpg")}
    @Test fun markdownBundleIsNotComic() {reject("readme.md","1.jpg")}
    @Test fun emptyComicRejected() {reject("ComicInfo.xml")}
    @Test fun measuredByteLimit() {assertTrue(runCatching {ComicArchive.readBounded(ByteArrayInputStream(ByteArray(100)),99)}.isFailure)}
    @Test fun crcMismatchRejected() {assertTrue(runCatching {ComicArchive.readBounded(ByteArrayInputStream(byteArrayOf(1,2)),10,1)}.isFailure)}
    @Test fun dimensionsBounded() {assertTrue(runCatching {ImageSize(60000,60000)}.isFailure);assertTrue(runCatching {ImageSize(0,10)}.isFailure)}
    @Test fun stableEntryBeforePageNumber() {val pages=listOf(ImagePage("1.jpg",100,200),ImagePage("2.jpg",100,200));assertEquals(1,ImagePosition(pageIndex=0,entryName="2.jpg").safe(pages).pageIndex)}
    @Test fun staleAndNonFinitePositionIsSafe() {
        val p=ImagePosition(pageIndex=999,scale=Float.NaN,centerX=Float.POSITIVE_INFINITY,listOffset=-1f,rotation=37).safe(listOf(ImagePage("1.jpg",100,200)))
        assertEquals(0,p.pageIndex);assertEquals(1f,p.scale);assertEquals(.5f,p.centerX);assertEquals(0f,p.listOffset);assertEquals(0,p.rotation)
    }
    @Test fun pageReadMatchesSourceWithoutPaths() {val f=zip("folder/1.png");try {assertArrayEquals(byteArrayOf(1,2,3),ComicArchive.page(f,ImagePage("folder/1.png",100,200)))}finally {f.delete()}}
    @Test fun tooManyEntries() {val f=zip(*(0..2000).map {"$it.jpg"}.toTypedArray());try {assertTrue(runCatching {ComicArchive.scan(f, {ImageSize(100,100)})}.isFailure)}finally {f.delete()}}
}
