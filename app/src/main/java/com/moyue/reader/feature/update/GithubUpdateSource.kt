package com.moyue.reader.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface UpdateSource {
    fun cancel() {}
    suspend fun latest():UpdateRelease
    suspend fun download(release:UpdateRelease,destination:File,onProgress:(Long)->Unit)
}

object UpdateJson {
    fun decode(text:String):UpdateRelease {
        val json=JSONObject(text)
        require(json.getInt("schemaVersion")==1 && json.getString("packageName")=="com.moyue.reader") {"更新信息格式无效"}
        return UpdateRelease(json.getString("versionName"),json.getLong("versionCode"),json.getInt("minSdk"),
            json.getString("apkUrl"),json.getString("sha256"),json.getLong("size"),json.optString("notes").take(6000))
    }
    fun encode(release:UpdateRelease)=JSONObject().put("schemaVersion",1).put("packageName","com.moyue.reader")
        .put("versionName",release.versionName).put("versionCode",release.versionCode).put("minSdk",release.minSdk)
        .put("apkUrl",release.apkUrl).put("sha256",release.sha256).put("size",release.size).put("notes",release.notes).toString()
}

class GithubUpdateSource:UpdateSource {
    @Volatile private var activeConnection:HttpsURLConnection?=null
    override fun cancel() {activeConnection?.disconnect()}
    private fun connection(address:String):HttpsURLConnection {
        var url=URL(address)
        repeat(6) {
            require(url.protocol=="https" && url.userInfo==null && url.port==-1 &&
                url.host in setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) {"更新地址不可信"}
            val conn=url.openConnection() as HttpsURLConnection
            activeConnection=conn
            conn.connectTimeout=15_000;conn.readTimeout=20_000;conn.instanceFollowRedirects=false;conn.useCaches=false
            conn.setRequestProperty("User-Agent","Moyue-Android-Updater")
            conn.setRequestProperty("Accept","application/octet-stream")
            val code=try {conn.responseCode} catch(e:Exception){conn.disconnect();throw e}
            if(code in listOf(301,302,303,307,308)) {
                val location=conn.getHeaderField("Location");conn.disconnect()
                if(location.isNullOrBlank())throw IOException("更新地址暂不可用")
                url=URL(url,location)
            } else {
                if(code!=200) {conn.disconnect();throw IOException(if(code==404)"发布方尚未提供更新信息，请稍后重试" else "更新服务器暂不可用，请稍后重试")}
                return conn
            }
        }
        throw IOException("更新地址跳转过多")
    }
    override suspend fun latest():UpdateRelease=withContext(Dispatchers.IO) {
        val conn=connection(UpdatePolicy.FEED_URL)
        try {
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use {input ->
                val buffer=ByteArray(8192)
                while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    require(bytes.size()+n<=65536) {"更新信息过大"};bytes.write(buffer,0,n)}
            }
            UpdateJson.decode(bytes.toString("UTF-8"))
        } finally {conn.disconnect();activeConnection=null}
    }
    override suspend fun download(release:UpdateRelease,destination:File,onProgress:(Long)->Unit)=withContext(Dispatchers.IO) {
        val conn=connection(release.apkUrl)
        try {
            val declared=conn.contentLengthLong
            require(declared<0 || declared==release.size) {"安装包大小与发布信息不一致"}
            conn.inputStream.use {input ->destination.outputStream().use {output ->
                val buffer=ByteArray(65536);var count=0L;var reported=-1
                while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    count+=n;require(count<=release.size) {"安装包大小超出发布信息"}
                    output.write(buffer,0,n)
                    val percent=(count*100/release.size).toInt();if(percent!=reported) {reported=percent;onProgress(count)}
                }
                require(count==release.size) {"安装包下载不完整，请重新下载"}
                output.fd.sync()
            }}
        } finally {conn.disconnect();activeConnection=null}
    }
}
