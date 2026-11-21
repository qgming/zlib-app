package com.qgming.zlib.core.data.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var runner: DownloadRunner

    @Inject
    lateinit var notifications: DownloadNotifications

    @Inject
    lateinit var repository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile
    private var controlPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DownloadServiceActions.PAUSE -> {
                val id = intent.getStringExtra(DownloadServiceActions.EXTRA_ID)
                controlPending = true
                job?.cancel()
                scope.launch {
                    id?.let { repository.pause(it) }
                    stopSelf(startId)
                }
            }

            DownloadServiceActions.CANCEL -> {
                val id = intent.getStringExtra(DownloadServiceActions.EXTRA_ID)
                controlPending = true
                job?.cancel()
                scope.launch {
                    id?.let { repository.cancel(it) }
                    stopSelf(startId)
                }
            }

            else -> startQueue(startId)
        }
        return START_NOT_STICKY
    }

    private fun startQueue(startId: Int) {
        controlPending = false
        startForeground(
            DownloadNotifications.NOTIFICATION_ID,
            notifications.progress(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                runner.runQueue()
            } finally {
                withContext(NonCancellable) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!controlPending) stopSelf(startId)
                }
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
