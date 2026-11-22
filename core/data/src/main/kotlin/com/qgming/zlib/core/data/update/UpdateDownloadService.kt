package com.qgming.zlib.core.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UpdateDownloadService : Service() {
    @Inject lateinit var downloads: UpdateDownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var latestStartId = 0
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(com.qgming.zlib.core.data.R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(ID, notification(downloads.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) startTransfer()
        return START_NOT_STICKY
    }

    private fun startTransfer() {
        val transfer = scope.launch {
            downloads.run { notifications.notify(ID, notification(it)) }
            if (downloads.state.value.phase == UpdateDownloadPhase.Ready) notifications.notify(READY_ID, notification(downloads.state.value))
        }
        job = transfer
        transfer.invokeOnCompletion {
            scope.launch {
                if (job !== transfer) return@launch
                job = null
                if (downloads.state.value.phase == UpdateDownloadPhase.Queued) startTransfer()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                }
            }
        }
    }

    private fun notification(state: UpdateDownloadState): Notification {
        val ready = state.phase == UpdateDownloadPhase.Ready
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (ready) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(getString(com.qgming.zlib.core.data.R.string.update_notification_title))
            .setContentText(getString(if (ready) com.qgming.zlib.core.data.R.string.update_notification_ready else com.qgming.zlib.core.data.R.string.update_notification_progress))
            .setOngoing(!ready).setOnlyAlertOnce(true).setAutoCancel(ready)
            .setProgress(if (ready) 0 else 100, if (state.total > 0) (state.written * 100 / state.total).toInt() else 0, !ready && state.total == 0L)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 920, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val CHANNEL = "zlib_updates"; private const val ID = 1201; private const val READY_ID = 1202 }
}
