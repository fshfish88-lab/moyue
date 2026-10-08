package com.moyue.reader.feature.update

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.moyue.reader.MainActivity
import com.moyue.reader.core.ui.MoyueTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class UpdateSmokeInstrumentation:Instrumentation() {
    private var mode:String?=null
    override fun onCreate(arguments:Bundle?) {mode=arguments?.getString("mode");super.onCreate(arguments);start()}
    override fun onStart() {
        val out=Bundle();val checks=JSONObject();val managers=mutableListOf<AppUpdateManager>()
        fun verify(value:Boolean,name:String){check(value){name};checks.put(name,true)}
        fun prefs(name:String)=targetContext.getSharedPreferences("update-qa-$name",Context.MODE_PRIVATE)
        fun directory(name:String)=File(targetContext.filesDir,"updates/qa-$name")
        class FixtureSource(var release:UpdateRelease,var file:File):UpdateSource {
            var requests=0;var offline=false;var pause=false
            override suspend fun latest():UpdateRelease {requests++;if(offline)throw java.io.IOException("offline");return release}
            override suspend fun download(release:UpdateRelease,destination:File,onProgress:(Long)->Unit) {
                withContext(Dispatchers.IO) {
                    if(pause){destination.writeBytes(byteArrayOf(1));onProgress(1);delay(60_000)}
                    file.copyTo(destination,overwrite=true);onProgress(destination.length())
                }
            }
        }
        suspend fun idle(manager:AppUpdateManager)=withTimeout(15_000){delay(200);while(manager.state.value.busy)delay(50)}
        fun node(text:String):android.view.accessibility.AccessibilityNodeInfo? {
            fun walk(n:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                n.refresh();if(n.text?.toString()==text || n.contentDescription?.toString()==text)return n
                for(i in 0 until n.childCount){n.getChild(i)?.let {walk(it)?.let {found->return found}}};return null
            }
            return uiAutomation.rootInActiveWindow?.let {walk(it)}
        }
        suspend fun waitText(text:String)=withTimeout(10_000){while(node(text)==null)delay(100)}
        try {runBlocking {
            if(mode=="cleanup") {
                for(name in listOf("main","install","reject")){directory(name).deleteRecursively();prefs(name).edit().clear().commit()}
                for(name in listOf("update-candidate.apk","update-wrong-signer.apk"))File(targetContext.getExternalFilesDir(null),name).delete()
                out.putString("stream","PASS owned update fixture cleanup");return@runBlocking
            }
            if(mode=="live") {
                val source=GithubUpdateSource()
                val release=withTimeout(60_000){source.latest()}
                verify(release.versionCode==ApkVerifier.installedCode(targetContext),"liveFeedMatchesInstalledRelease")
                verify(release.decision(ApkVerifier.installedCode(targetContext),android.os.Build.VERSION.SDK_INT)==UpdateDecision.CURRENT,"liveFeedCurrentVersionNotOfferedAgain")
                val downloaded=File(directory("reject"),"live.apk");downloaded.parentFile!!.mkdirs()
                try {
                    withTimeout(180_000){source.download(release,downloaded){}}
                    verify(downloaded.length()==release.size && ApkVerifier.hash(downloaded).equals(release.sha256,true),"actualGithubApkDownloadMatchesPublishedDigest")
                    val archive=requireNotNull(targetContext.packageManager.getPackageArchiveInfo(downloaded.absolutePath,0))
                    verify(archive.packageName==targetContext.packageName && ApkVerifier.versionCode(archive)==release.versionCode && archive.versionName==release.versionName,"actualGithubApkMetadataMatchesFeed")
                    out.putString("stream","PASS public GitHub update feed and APK download")
                } finally {source.cancel();downloaded.delete()}
                return@runBlocking
            }
            if(mode=="install") {
                val manager=AppUpdateManager(targetContext,preferences=prefs("install"),directory=directory("install"));managers+=manager
                runOnMainSync {manager.foreground()};idle(manager)
                verify(manager.state.value.phase==UpdatePhase.READY,"restoredVerifiedInstallerPackage")
                verify(targetContext.packageManager.canRequestPackageInstalls(),"installerPermissionGranted")
                val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                runOnMainSync {manager.install(activity)};idle(manager)
                verify(manager.state.value.phase==UpdatePhase.READY && !manager.state.value.showPrompt,"systemInstallerLaunched")
                // Keep the source activity and its URI grant alive until staging has completed.
                withTimeout(20_000) {
                    while(listOf("UPDATE","Update","INSTALL","Install","安装","更新").none {node(it)!=null})delay(100)
                }
                verify(true,"systemConfirmationVisibleAfterApkStaging")
                return@runBlocking
            }
            val candidate=File(targetContext.getExternalFilesDir(null),"update-candidate.apk")
            val wrongSigner=File(targetContext.getExternalFilesDir(null),"update-wrong-signer.apk")
            val candidateInfo=targetContext.packageManager.getPackageArchiveInfo(candidate.absolutePath,0) ?: error("candidate missing")
            val release=UpdateRelease(requireNotNull(candidateInfo.versionName),ApkVerifier.versionCode(candidateInfo),26,
                "https://github.com/fshfish88-lab/moyue/releases/download/v${candidateInfo.versionName}/Moyue-${candidateInfo.versionName}.apk",
                ApkVerifier.hash(candidate),candidate.length(),"自动检查并安全下载新版本")
            verify(release.versionCode>ApkVerifier.installedCode(targetContext),"candidateIsNewer")
            verify(UpdateJson.decode(UpdateJson.encode(release))==release,"metadataRoundTrip")
            verify(runCatching {UpdateJson.decode("{\"schemaVersion\":2}")}.isFailure,"invalidMetadataRejected")
            var now=100*UpdatePolicy.DAY
            val source=FixtureSource(release,candidate)
            for(name in listOf("main","install","reject")){prefs(name).edit().clear().commit();directory(name).deleteRecursively()}
            val manager=AppUpdateManager(targetContext,source,prefs("main"),directory("main")){now};managers+=manager
            runOnMainSync {manager.setAutomatic(false);manager.foreground()};idle(manager)
            verify(source.requests==0,"automaticSwitchSuppressesNetwork")
            runOnMainSync {manager.checkManually()};idle(manager)
            verify(source.requests==1 && manager.state.value.showPrompt,"manualCheckBypassesSwitch")
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {activity.setContent {MoyueTheme {AppUpdateDialogs(manager)}}}
            waitText("下载更新");verify(node("发现新版本 V${release.versionName}")!=null,"newReleaseDialogVisible")
            runOnMainSync {manager.dismiss()};verify(!manager.state.value.showPrompt,"laterDoesNotDownload")
            runOnMainSync {manager.setAutomatic(true);manager.foreground()};idle(manager)
            verify(source.requests==1,"successfulAutomaticCheckThrottled")
            now+=UpdatePolicy.DAY;source.offline=true
            runOnMainSync {manager.foreground()};idle(manager)
            verify(source.requests==2 && !manager.state.value.showPrompt,"offlineAutomaticCheckIsQuiet")
            runOnMainSync {manager.foreground()};idle(manager)
            verify(source.requests==2,"failedAutomaticCheckBacksOff")
            source.offline=false;source.pause=true
            runOnMainSync {manager.showPrompt();manager.download()}
            waitText("取消下载")
            var cancelNode=node("取消下载")!!
            while(!cancelNode.isClickable)cancelNode=cancelNode.parent ?: error("Cancel text has no clickable button parent")
            verify(cancelNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"cancelButtonInvoked")
            idle(manager);delay(100)
            verify(manager.state.value.phase==UpdatePhase.IDLE && !File(directory("main"),"update.part").exists(),"cancelRemovesPartialDownload")
            source.pause=false
            source.release=release.copy(sha256="0".repeat(64));runOnMainSync {manager.checkManually()};idle(manager)
            runOnMainSync {manager.download()};idle(manager)
            verify(manager.state.value.phase==UpdatePhase.IDLE && !File(directory("main"),"update.apk").exists(),"digestMismatchNeverBecomesInstallable")
            source.file=wrongSigner;source.release=release.copy(sha256=ApkVerifier.hash(wrongSigner),size=wrongSigner.length())
            runOnMainSync {manager.checkManually()};idle(manager);runOnMainSync {manager.download()};idle(manager)
            verify(manager.state.value.phase==UpdatePhase.IDLE && manager.state.value.message.contains("签名"),"differentSigningKeyRejected")
            source.file=candidate;source.release=release
            runOnMainSync {manager.checkManually()};idle(manager);runOnMainSync {manager.download()};idle(manager)
            verify(manager.state.value.phase==UpdatePhase.READY,"verifiedNewReleaseReady")
            val uri=ApkVerifier.installIntent(targetContext,File(directory("main"),"update.apk")).data!!
            verify(uri.scheme=="content" && targetContext.contentResolver.openInputStream(uri)!!.use {it.readBytes()}.contentEquals(candidate.readBytes()),"fileProviderSuppliesExactVerifiedApk")
            runOnMainSync {manager.setAutomatic(false);manager.close()}
            val restored=AppUpdateManager(targetContext,source,prefs("main"),directory("main"));managers+=restored
            runOnMainSync {restored.foreground()};idle(restored)
            verify(!restored.state.value.automatic && restored.state.value.phase==UpdatePhase.READY,"downloadAndSwitchSurviveProcessRecreation")
            runOnMainSync {restored.permissionDenied()}
            verify(restored.state.value.phase==UpdatePhase.READY,"permissionDenialPreservesDownload")
            prefs("install").edit().putBoolean("automatic",false).putString("ready",UpdateJson.encode(release)).commit()
            directory("install").mkdirs();candidate.copyTo(File(directory("install"),"update.apk"),overwrite=true)
            runOnMainSync {activity.finish()}
            out.putString("stream","PASS update checks=${checks.length()}")
        }} catch(error:Throwable){out.putString("stream","FAIL ${error.stackTraceToString()}")}
        finally {for(manager in managers)runOnMainSync {manager.close()};out.putString("checks",checks.toString())
            if(mode=="install" && !out.getString("stream").orEmpty().startsWith("FAIL"))out.putString("stream","PASS system installer handoff")
            finish(if(out.getString("stream").orEmpty().startsWith("PASS"))-1 else 0,out)}
    }
}
