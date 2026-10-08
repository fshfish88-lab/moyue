package com.moyue.reader.feature.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkVerifier {
    @Suppress("DEPRECATION")
    fun versionCode(info:PackageInfo):Long=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    fun installedCode(context:Context)=versionCode(context.packageManager.getPackageInfo(context.packageName,0))
    fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input ->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Suppress("DEPRECATION")
    fun verify(context:Context,file:File,release:UpdateRelease) {
        require(file.isFile && file.length()==release.size) {"安装包下载不完整，请重新下载"}
        require(hash(file).equals(release.sha256,true)) {"安装包校验失败，请重新下载"}
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)) {"安装包无法识别"}
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        require(archive.packageName==context.packageName && versionCode(archive)==release.versionCode && archive.versionName==release.versionName) {"安装包版本或包名不一致"}
        require(release.decision(versionCode(installed),Build.VERSION.SDK_INT)==UpdateDecision.AVAILABLE) {"该安装包不适用于当前版本"}
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=Build.VERSION.SDK_INT) {"当前 Android 系统不支持该版本"}
        fun signers(info:PackageInfo):Set<String> {
            val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map {MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)}}.toSet()
        }
        val original=signers(installed)
        require(original.isNotEmpty() && signers(archive)==original) {"安装包发布签名不一致，已停止安装"}
    }
    fun installIntent(context:Context,file:File):Intent=Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context,context.packageName+".files",file),"application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
