package com.qgming.zlib.core.designsystem

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.Refresh

enum class ZlibDownloadAction(private val labelRes: Int, internal val icon: ImageVector) {
    Pause(TextR.string.text_pause_download, MiuixIcons.Glass.Normal.Pause),
    Resume(TextR.string.text_resume_download, MiuixIcons.Glass.Normal.Play),
    Retry(TextR.string.text_retry_download, MiuixIcons.Glass.Normal.Refresh),
    Remove(TextR.string.text_remove_task_record, MiuixIcons.Glass.Normal.Close);

    val label: String get() = appText(labelRes)
}

@Composable
fun ZlibDownloadActionButton(action: ZlibDownloadAction, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp), backgroundColor = Color.Transparent) {
        Icon(action.icon, action.label, modifier = Modifier.size(24.dp))
    }
}
