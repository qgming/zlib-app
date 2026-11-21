package com.qgming.zlib.core.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.qgming.zlib.core.database.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "图书下载进度与结果"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun progress(item: DownloadEntity?): Notification = builder(item, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(
            100,
            item?.let { percent(it.bytesWritten, it.totalBytes) } ?: 0,
            item == null || item.totalBytes == null,
        )
        .build()

    fun update(item: DownloadEntity) {
        manager.notify(NOTIFICATION_ID, progress(item))
    }

    fun completed(item: DownloadEntity, bytes: Long, total: Long?) {
        manager.notify(
            NOTIFICATION_ID,
            builder(item.copy(state = "COMPLETED", bytesWritten = bytes, totalBytes = total), true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun failed(item: DownloadEntity, code: String) {
        manager.notify(
            NOTIFICATION_ID,
            builder(item, true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentText("下载失败：${label(code)}")
                .build(),
        )
    }

    private fun builder(item: DownloadEntity?, completed: Boolean): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item?.title?.takeIf { it.isNotBlank() } ?: "Zlib 下载")
            .setContentText(
                item?.let {
                    if (completed) "下载完成" else progressText(it.bytesWritten, it.totalBytes)
                } ?: "准备下载…",
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        launchIntent()?.let { builder.setContentIntent(it) }
        if (item != null && !completed) {
            builder.addAction(0, "暂停", action(DownloadServiceActions.PAUSE, item.id, 1))
            builder.addAction(0, "取消", action(DownloadServiceActions.CANCEL, item.id, 2))
        }
        return builder
    }

    private fun action(action: String, id: String, offset: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(action)
            .putExtra(DownloadServiceActions.EXTRA_ID, id)
        return PendingIntent.getService(
            context,
            id.hashCode() + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun percent(written: Long, total: Long?): Int {
        if (total == null || total <= 0) return 0
        return (written * 100 / total).toInt().coerceIn(0, 100)
    }

    private fun progressText(written: Long, total: Long?): String {
        val w = "%.1f MB".format(written / 1024.0 / 1024.0)
        return if (total == null) w else "$w / %.1f MB".format(total / 1024.0 / 1024.0)
    }

    private fun label(code: String): String = when (code) {
        "quota" -> "今日下载额度已用完"
        "session" -> "登录已失效"
        "domain" -> "域名被拦截"
        "network" -> "网络错误"
        "storage" -> "写入失败"
        "http_404" -> "文件不存在"
        else -> "请重试"
    }

    companion object {
        const val CHANNEL_ID = "zlib_downloads"
        const val NOTIFICATION_ID = 1001
    }
}
