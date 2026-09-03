package com.moyue.reader.feature.importbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportProgressDialog(state: ImportState, onDismiss: () -> Unit) {
    when (state) {
        is ImportState.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("正在导入") },
            text = {
                Column {
                    Text(state.stage.label)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            },
        )
        is ImportState.Completed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { Button(onClick = onDismiss) { Text("开始阅读") } },
            title = { Text("导入完成") },
            text = { Text("书籍已安全保存到书架。") },
        )
        is ImportState.RecoverableError -> ErrorDialog("暂时无法导入", state.message, onDismiss)
        is ImportState.FatalError -> ErrorDialog("导入失败", state.message, onDismiss)
        is ImportState.Cancelled -> ErrorDialog("已取消", "未创建书籍，临时文件已清理。", onDismiss)
    }
}

@Composable private fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text(title) },
        text = { Text(message) },
    )
}

private val ImportStage.label get() = when (this) {
    ImportStage.WAITING -> "正在准备"
    ImportStage.COPYING -> "正在复制文件"
    ImportStage.DETECTING -> "正在检测格式与编码"
    ImportStage.PARSING -> "正在解析章节"
    ImportStage.SAVING -> "正在安全保存"
    ImportStage.COMPLETED -> "已完成"
}
