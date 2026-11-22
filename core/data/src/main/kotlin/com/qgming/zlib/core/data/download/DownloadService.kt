package com.qgming.zlib.core.data.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var runner: DownloadRunner

    @Inject
    lateinit var notifications: DownloadNotifications

    @Inject
    lateinit var repository: DownloadRepository

    // Service lifecycle and command bookkeeping share Main; the runner performs its IO off Main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pendingCommands = 0
    private var latestStartId = 0
    private var queueRequested = false
    private val controlMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getStringExtra(DownloadServiceActions.EXTRA_ID)
        latestStartId = startId
        pendingCommands++
        // Post immediately, including commands received while a foreground launch is pending.
        startForeground(DownloadNotifications.NOTIFICATION_ID, notifications.progress(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            controlMutex.withLock {
                try {
                    if (id != null && action in setOf(DownloadServiceActions.PAUSE, DownloadServiceActions.CANCEL, DownloadServiceActions.REMOVE)) {
                        runner.control(
                            id,
                            stopActive = {
                                stopDownloadWorker(job, runner::cancelActiveTransfer) { job = null }
                            },
                            apply = { repository.finishControl(action!!, id) },
                        )
                    }
                } finally {
                    pendingCommands--
                    if (pendingCommands == 0) startQueue()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startQueue() {
        if (job?.isActive == true) {
            queueRequested = true
            return
        }
        queueRequested = false
        val worker = scope.launch { runner.runQueue() }
        job = worker
        // Completion must not await the command mutex: a control holds it while joining this job.
        worker.invokeOnCompletion {
            scope.launch {
                controlMutex.withLock {
                    if (job === worker) {
                        job = null
                        if (pendingCommands == 0) {
                            if (queueRequested) startQueue() else {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf(latestStartId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        runner.cancelActiveTransfer()
        scope.cancel()
        super.onDestroy()
    }
}
