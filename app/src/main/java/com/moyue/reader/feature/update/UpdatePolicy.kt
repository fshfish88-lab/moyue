package com.moyue.reader.feature.update

import java.net.URI

enum class UpdateDecision { AVAILABLE, CURRENT, INCOMPATIBLE }

data class UpdateRelease(val versionName:String,val versionCode:Long,val minSdk:Int,val apkUrl:String,
    val sha256:String,val size:Long,val notes:String) {
    init {
        require(versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) {"更新版本信息无效"}
        require(versionCode>0 && minSdk>=26 && size in 1..UpdatePolicy.MAX_APK_BYTES) {"更新安装包信息无效"}
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) {"更新缺少完整校验信息"}
        val uri=URI(apkUrl)
        require(uri.scheme=="https" && uri.host=="github.com" && uri.port==-1 && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null &&
            uri.rawPath=="/fshfish88-lab/moyue/releases/download/v$versionName/Moyue-$versionName.apk") {"更新下载地址无效"}
    }
    fun decision(installedCode:Long,sdk:Int)=when {
        versionCode<=installedCode -> UpdateDecision.CURRENT
        minSdk>sdk -> UpdateDecision.INCOMPATIBLE
        else -> UpdateDecision.AVAILABLE
    }
}

object UpdatePolicy {
    const val HOUR=60L*60*1000
    const val DAY=24*HOUR
    const val MAX_APK_BYTES=150L*1024*1024
    const val FEED_URL="https://github.com/fshfish88-lab/moyue/releases/latest/download/update.json"
    fun shouldAutoCheck(enabled:Boolean,lastAttempt:Long,lastSuccess:Long,now:Long):Boolean {
        fun elapsed(time:Long,interval:Long)=time==0L || now<time || now-time>=interval
        return enabled && elapsed(lastAttempt,HOUR) && elapsed(lastSuccess,DAY)
    }
}
