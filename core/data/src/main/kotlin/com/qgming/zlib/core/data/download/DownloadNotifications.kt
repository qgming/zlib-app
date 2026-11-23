package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.qgming.zlib.core.common.R
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
            appText(TextR.string.text_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appText(TextR.string.text_book_download_progress_and_results)
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
            COMPLETED_ID,
            builder(item.copy(state = "COMPLETED", bytesWritten = bytes, totalBytes = total), true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun failed(item: DownloadEntity, code: String) {
        manager.notify(
            COMPLETED_ID,
            builder(item, true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentText(appText(TextR.string.text_download_failed, label(code)))
                .build(),
        )
    }

    private fun builder(item: DownloadEntity?, completed: Boolean): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.zlib_download_title))
            .setContentText(
                item?.let {
                    if (completed) appText(TextR.string.text_download_complete) else progressText(it.bytesWritten, it.totalBytes)
                } ?: appText(TextR.string.text_preparing_download),
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        launchIntent()?.let { builder.setContentIntent(it) }
        if (item != null && !completed) {
            builder.addAction(0, appText(TextR.string.text_pause), action(DownloadServiceActions.PAUSE, item.id, 1))
            builder.addAction(0, appText(TextR.string.text_cancel), action(DownloadServiceActions.CANCEL, item.id, 2))
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
        intent.putExtra(DownloadNavigation.EXTRA_DESTINATION, DownloadNavigation.DOWNLOADS)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        "quota" -> appText(TextR.string.text_daily_download_quota_exhausted)
        "session" -> appText(TextR.string.text_session_expired)
        "domain" -> appText(TextR.string.text_domain_blocked)
        "network" -> appText(TextR.string.text_network_error)
        "storage" -> appText(TextR.string.text_write_failed)
        "storage_permission" -> appText(TextR.string.text_cannot_access_the_download_folder_grant_access_again)
        "range" -> appText(TextR.string.text_the_resume_position_is_invalid_retry_the_download)
        "invalid" -> appText(TextR.string.text_file_verification_failed_please_retry)
        "incomplete" -> appText(TextR.string.text_download_incomplete_progress_is_saved)
        "http_404" -> appText(TextR.string.text_file_not_found)
        else -> appText(TextR.string.text_please_retry)
    }

    companion object {
        const val CHANNEL_ID = "zlib_downloads"
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_ID = 1002
    }
}
