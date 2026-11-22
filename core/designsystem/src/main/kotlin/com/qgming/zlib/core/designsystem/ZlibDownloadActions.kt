package com.qgming.zlib.core.designsystem

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

enum class ZlibDownloadAction(val label: String, internal val icon: ImageVector) {
    Pause("暂停下载", MiuixIcons.Glass.Normal.Pause),
    Resume("继续下载", MiuixIcons.Glass.Normal.Play),
    Retry("重试下载", MiuixIcons.Glass.Normal.Refresh),
    Remove("移除任务记录", MiuixIcons.Glass.Normal.Close),
}

@Composable
fun ZlibDownloadActionButton(action: ZlibDownloadAction, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp), backgroundColor = Color.Transparent) {
        Icon(action.icon, action.label, modifier = Modifier.size(24.dp))
    }
}
