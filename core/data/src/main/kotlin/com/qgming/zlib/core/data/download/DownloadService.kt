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
    private var foregroundStarted = false
    private var stopping = false
    private val controlMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        val id = intent?.getStringExtra(DownloadServiceActions.EXTRA_ID)
        val controlAction = action?.takeIf {
            id != null && it in setOf(
                DownloadServiceActions.PAUSE,
                DownloadServiceActions.CANCEL,
                DownloadServiceActions.REMOVE,
            )
        }
        val requiresForeground = controlAction == null
        if (stopping) {
            if (requiresForeground) {
                runCatching {
                    startForeground(DownloadNotifications.NOTIFICATION_ID, notifications.progress(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (requiresForeground) {
            queueRequested = true
            // Post immediately for foreground launches, preserving active notification updates if already foreground.
            if (!foregroundStarted) {
                try {
                    startForeground(DownloadNotifications.NOTIFICATION_ID, notifications.progress(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    foregroundStarted = true
                } catch (_: IllegalStateException) {
                    stopForForegroundLimit(DownloadCodes.FOREGROUND_UNAVAILABLE)
                    return START_NOT_STICKY
                } catch (_: SecurityException) {
                    stopForForegroundLimit(DownloadCodes.FOREGROUND_UNAVAILABLE)
                    return START_NOT_STICKY
                }
            }
        }
        pendingCommands++
        scope.launch {
            controlMutex.withLock {
                try {
                    if (controlAction != null && id != null) {
                        runner.control(
                            id,
                            stopActive = {
                                stopDownloadWorker(job, runner::cancelActiveTransfer) { job = null }
                            },
                            apply = { repository.finishControl(controlAction, id) },
                        )
                    }
                } finally {
                    pendingCommands--
                    if (pendingCommands == 0) {
                        if (queueRequested || job?.isActive == true) {
                            startQueue()
                        } else if (foregroundStarted) {
                            // A PAUSE/CANCEL/REMOVE stopped the active item while already foreground;
                            // continue draining any remaining queued items.
                            startQueue()
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            foregroundStarted = false
                            stopSelf(latestStartId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startQueue() {
        if (stopping) return
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
                                foregroundStarted = false
                                stopSelf(latestStartId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            stopForForegroundLimit(DownloadCodes.FOREGROUND_TIMEOUT)
        }
    }

    private fun stopForForegroundLimit(reason: String) {
        stopping = true
        job?.cancel()
        runner.stopForForegroundLimit(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        runner.cancelActiveTransfer()
        scope.cancel()
        super.onDestroy()
    }
}
