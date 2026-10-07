package com.moyue.reader.feature.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.moyue.reader.core.document.*
import java.io.ByteArrayInputStream
import java.io.File

object ImageProbe {
    fun inspect(bytes: ByteArray): ImageSize {
        require(signature(bytes) != null) { "图片内容不是支持的 JPG / PNG / WebP" }
        if (signature(bytes) == "webp") {
            // VP8X flags include animation; animated files are deferred to a later stage.
            require(!(bytes.size > 20 && String(bytes,12,4,Charsets.US_ASCII)=="VP8X" && bytes[20].toInt() and 2 != 0)) { "暂不支持动态 WebP" }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        val original = ImageSize(bounds.outWidth,bounds.outHeight)
        val sample = preview(bytes,256)
        sample.recycle()
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        return if (orientation in setOf(90,270)) ImageSize(original.height,original.width) else original
    }
    fun file(file: File): ImageSize {
        require(file.length() <= ComicArchive.MAX_SOURCE) { "图片文件不能超过 200 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { stream -> val header=ByteArray(32); val count=stream.read(header); require(count>=12 && signature(header)!=null) { "图片格式不正确" }
            require(!(signature(header)=="webp" && String(header,12,4,Charsets.US_ASCII)=="VP8X" && header[20].toInt() and 2 != 0)) { "暂不支持动态 WebP" } }
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        val size=ImageSize(bounds.outWidth,bounds.outHeight)
        val check = BitmapFactory.decodeFile(file.absolutePath,options(size.width,size.height,256)) ?: error("图片损坏，无法解码")
        check.recycle()
        val rotation = ExifInterface(file).rotationDegrees
        return if (rotation in setOf(90,270)) ImageSize(size.height,size.width) else size
    }
    fun preview(bytes: ByteArray, edge: Int = 512): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        ImageSize(bounds.outWidth,bounds.outHeight)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options(bounds.outWidth,bounds.outHeight,edge)) ?: error("图片损坏，无法解码")
        val exif=runCatching {ExifInterface(ByteArrayInputStream(bytes))}.getOrNull()
        val matrix=android.graphics.Matrix()
        if(exif?.isFlipped==true) matrix.postScale(-1f,1f)
        matrix.postRotate((exif?.rotationDegrees ?: 0).toFloat())
        if(matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also { if(it!==bitmap) bitmap.recycle() }
    }
    fun options(width:Int,height:Int,edge:Int): BitmapFactory.Options {
        var sample=1
        while(maxOf(width,height)/sample>edge) sample*=2
        return BitmapFactory.Options().apply { inSampleSize=sample }
    }
    fun signature(b:ByteArray): String? = when {
        b.size>=3 && b[0]==0xff.toByte() && b[1]==0xd8.toByte() && b[2]==0xff.toByte() -> "jpg"
        b.size>=8 && b.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) -> "png"
        b.size>=12 && String(b,0,4,Charsets.US_ASCII)=="RIFF" && String(b,8,4,Charsets.US_ASCII)=="WEBP" -> "webp"
        else -> null
    }
}
