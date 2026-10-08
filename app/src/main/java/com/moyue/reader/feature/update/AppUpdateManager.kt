package com.moyue.reader.feature.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, VERIFYING, READY }
data class AppUpdateState(val automatic:Boolean=true,val phase:UpdatePhase=UpdatePhase.IDLE,val release:UpdateRelease?=null,
    val message:String="打开 App 时自动检查新版本",val progress:Float=0f,val showPrompt:Boolean=false) {
    val busy get()=phase in setOf(UpdatePhase.CHECKING,UpdatePhase.DOWNLOADING,UpdatePhase.VERIFYING)
}

class AppUpdateManager(private val context:Context,private val source:UpdateSource=GithubUpdateSource(),
    private val preferences:SharedPreferences=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE),
    private val directory:File=File(context.filesDir,"updates"),private val clock:()->Long=System::currentTimeMillis) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutableState=MutableStateFlow(AppUpdateState(automatic=preferences.getBoolean("automatic",true)))
    val state=mutableState.asStateFlow()
    private var job:Job?=null
    private var restored=false
    private val apk get()=File(directory,"update.apk")
    private val partial get()=File(directory,"update.part")
    fun setAutomatic(enabled:Boolean) {preferences.edit().putBoolean("automatic",enabled).apply();mutableState.update {it.copy(automatic=enabled)}}
    fun dismiss() {mutableState.update {it.copy(showPrompt=false)}}
    fun showPrompt() {if(state.value.release!=null)mutableState.update {it.copy(showPrompt=true)}}
    private fun start(block:suspend ()->Unit) {if(job?.isActive==true)return;job=scope.launch {block()}}
    fun foreground()=start {restore();if(UpdatePolicy.shouldAutoCheck(state.value.automatic,preferences.getLong("lastAttempt",0),preferences.getLong("lastSuccess",0),clock()))check(false)}
    fun checkManually()=start {restore();check(true)}
    private suspend fun restore() {
        if(restored)return
        restored=true
        val saved=preferences.getString("ready",null)
        val release=withContext(Dispatchers.IO) {
            directory.mkdirs();partial.delete()
            if(saved==null){apk.delete();null} else try {
                val info=UpdateJson.decode(saved);ApkVerifier.verify(context,apk,info);info
            } catch(_:Exception){apk.delete();preferences.edit().remove("ready").apply();null}
        }
        if(release!=null)mutableState.update {it.copy(phase=UpdatePhase.READY,release=release,message="V${release.versionName} 已下载，点击安装")}
    }
    private suspend fun check(manual:Boolean) {
        val previous=state.value
        mutableState.update {it.copy(phase=UpdatePhase.CHECKING,message="正在检查更新…")}
        preferences.edit().putLong("lastAttempt",clock()).apply()
        try {
            val release=source.latest()
            val decision=release.decision(ApkVerifier.installedCode(context),Build.VERSION.SDK_INT)
            preferences.edit().putLong("lastSuccess",clock()).apply()
            if(decision==UpdateDecision.AVAILABLE) {
                val ready=previous.phase==UpdatePhase.READY && previous.release==release
                mutableState.update {it.copy(phase=if(ready)UpdatePhase.READY else UpdatePhase.IDLE,release=release,
                    message=if(ready)"V${release.versionName} 已下载，点击安装" else "发现新版本 V${release.versionName}",showPrompt=manual || it.automatic)}
            } else {
                withContext(Dispatchers.IO){apk.delete();preferences.edit().remove("ready").apply()}
                mutableState.update {it.copy(phase=UpdatePhase.IDLE,release=null,showPrompt=false,
                    message=if(decision==UpdateDecision.CURRENT)"已经是最新版本" else "新版本需要更高的 Android 系统版本")}
            }
        } catch(error:CancellationException) {throw error
        } catch(_:Exception) {
            mutableState.update {it.copy(phase=if(previous.phase==UpdatePhase.READY)UpdatePhase.READY else UpdatePhase.IDLE,
                message=if(previous.phase==UpdatePhase.READY)"检查失败，已下载的更新仍可安装" else "无法检查更新，请确认网络后重试",showPrompt=manual && previous.release!=null)}
        }
    }
    fun download()=start {
        val release=state.value.release ?: return@start
        mutableState.update {it.copy(phase=UpdatePhase.DOWNLOADING,progress=0f,message="正在下载更新…",showPrompt=true)}
        try {
            withContext(Dispatchers.IO){directory.mkdirs();partial.delete()}
            source.download(release,partial) {bytes->mutableState.update {it.copy(progress=(bytes.toFloat()/release.size).coerceIn(0f,1f))}}
            mutableState.update {it.copy(phase=UpdatePhase.VERIFYING,message="正在校验安装包…")}
            withContext(Dispatchers.IO) {
                ApkVerifier.verify(context,partial,release)
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING)
                preferences.edit().putString("ready",UpdateJson.encode(release)).commit()
            }
            mutableState.update {it.copy(phase=UpdatePhase.READY,message="下载完成，点击安装更新",showPrompt=true,progress=1f)}
        } catch(error:CancellationException) {
            mutableState.update {it.copy(phase=UpdatePhase.IDLE,message="下载已取消，可重新下载")};throw error
        } catch(error:Exception) {
            mutableState.update {it.copy(phase=UpdatePhase.IDLE,message=if(error is IllegalArgumentException)error.message ?: "安装包校验失败" else "下载失败，请确认网络后重试",showPrompt=true)}
        } finally {withContext(NonCancellable+Dispatchers.IO){partial.delete()}}
    }
    fun cancelDownload() {if(state.value.phase==UpdatePhase.DOWNLOADING){job?.cancel();source.cancel()}}
    fun install(activityContext:Context)=start {
        val release=state.value.release ?: return@start
        if(state.value.phase!=UpdatePhase.READY)return@start
        mutableState.update {it.copy(phase=UpdatePhase.VERIFYING,message="正在校验安装包…")}
        try {
            withContext(Dispatchers.IO){ApkVerifier.verify(context,apk,release)}
            mutableState.update {it.copy(phase=UpdatePhase.READY,showPrompt=false,message="请在系统页面确认覆盖安装")}
            activityContext.startActivity(ApkVerifier.installIntent(context,apk))
        } catch(_:Exception){mutableState.update {it.copy(phase=UpdatePhase.IDLE,message="无法打开安装包，请重新下载",showPrompt=true)}}
    }
    fun permissionDenied() {mutableState.update {it.copy(message="未允许安装更新，安装包已保留，可稍后重试",showPrompt=true)}}
    fun close() {scope.cancel()}
}
