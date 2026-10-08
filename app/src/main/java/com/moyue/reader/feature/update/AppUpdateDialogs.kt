package com.moyue.reader.feature.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateDialogs(manager:AppUpdateManager) {
    val state by manager.state.collectAsState()
    val context=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls())manager.install(context) else manager.permissionDenied()
    }
    val release=state.release ?: return
    if(!state.showPrompt)return
    AlertDialog(onDismissRequest=manager::dismiss,title={Text(if(state.phase==UpdatePhase.READY)"更新已准备好" else "发现新版本 V${release.versionName}")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("V${release.versionName} · %.1f MB".format(release.size/1048576.0))
            if(release.notes.isNotBlank())Text(release.notes,modifier=Modifier.padding(top=12.dp))
            Text(state.message,modifier=Modifier.padding(top=12.dp))
            if(state.phase==UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress={state.progress},modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                Text("${(state.progress*100).toInt()}%",style=MaterialTheme.typography.labelMedium)
            }
            if(state.phase==UpdatePhase.READY)Text("直接覆盖安装，保留已有书籍和阅读记录。",modifier=Modifier.padding(top=12.dp))
        }},confirmButton={TextButton(enabled=!state.busy,onClick={
            if(state.phase==UpdatePhase.READY) {
                if(context.packageManager.canRequestPackageInstalls())manager.install(context)
                else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
            } else manager.download()
        }) {Text(if(state.phase==UpdatePhase.READY)"安装更新" else "下载更新")}},
        dismissButton={Row {
            if(state.phase==UpdatePhase.DOWNLOADING)TextButton(onClick=manager::cancelDownload){Text("取消下载")}
            TextButton(onClick=manager::dismiss){Text("稍后")}
        }})
}
