package com.qgming.zlib.core.data.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.core.content.ContextCompat
import com.qgming.zlib.core.data.download.DownloadDirectoryStore
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.apkFor
import com.qgming.zlib.core.network.ReleaseApkDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class UpdateDownloadPhase { Idle, Starting, Queued, Downloading, Verifying, Saving, Ready, Failed }
enum class UpdateDownloadFailure { Directory, Asset, Transfer, Verification, Storage }
data class UpdateDownloadState(
    val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val release: AppRelease? = null,
    val written: Long = 0,
    val total: Long = 0,
    val savedUri: String? = null,
    val failure: UpdateDownloadFailure? = null,
) {
    val busy: Boolean get() = phase in setOf(UpdateDownloadPhase.Starting, UpdateDownloadPhase.Queued, UpdateDownloadPhase.Downloading, UpdateDownloadPhase.Verifying, UpdateDownloadPhase.Saving)
}

@Singleton
class UpdateDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directory: DownloadDirectoryStore,
    private val files: UpdateApkStore,
) {
    private val downloader = ReleaseApkDownloader()
    private val _state = MutableStateFlow(UpdateDownloadState())
    val state = _state.asStateFlow()
    @Volatile private var attempt = 0L
    @Volatile private var worker: Job? = null
    private var restored = false

    suspend fun restoreReady() {
        if (restored) return
        restored = true
        val saved = withContext(Dispatchers.IO) { files.restoreReady() } ?: return
        if (_state.value.phase == UpdateDownloadPhase.Idle) {
            _state.value = UpdateDownloadState(phase = UpdateDownloadPhase.Ready, release = saved.first, savedUri = saved.second)
        }
    }

    suspend fun setDirectory(uri: Uri): Boolean = directory.setTree(uri) is AppResult.Success

    suspend fun start(release: AppRelease) {
        if (_state.value.busy) return
        val id = ++attempt
        _state.value = UpdateDownloadState(phase = UpdateDownloadPhase.Starting, release = release)
        worker?.join()
        currentCoroutineContext().ensureActive()
        if (attempt != id) return
        if (directory.ensureLoaded() == null) {
            if (attempt == id) _state.value = UpdateDownloadState(release = release, phase = UpdateDownloadPhase.Failed, failure = UpdateDownloadFailure.Directory)
            return
        }
        if (release.apkFor(Build.SUPPORTED_ABIS.toList()) == null) {
            _state.value = UpdateDownloadState(release = release, phase = UpdateDownloadPhase.Failed, failure = UpdateDownloadFailure.Asset)
            return
        }
        if (attempt != id) return
        try {
            _state.update { it.copy(phase = UpdateDownloadPhase.Queued) }
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
        } catch (_: Exception) {
            _state.value = _state.value.copy(phase = UpdateDownloadPhase.Failed, failure = UpdateDownloadFailure.Transfer)
        }
    }

    suspend fun run(progress: (UpdateDownloadState) -> Unit) = withContext(Dispatchers.IO) {
        val initial = _state.value
        val release = initial.release ?: return@withContext
        val asset = release.apkFor(Build.SUPPORTED_ABIS.toList()) ?: return@withContext
        if (initial.phase != UpdateDownloadPhase.Queued) return@withContext
        worker = currentCoroutineContext().job
        val id = attempt
        var temporary: File? = null
        var failure = UpdateDownloadFailure.Transfer
        try {
            val target = files.temporary()
            temporary = target
            _state.value = initial.copy(phase = UpdateDownloadPhase.Downloading, total = asset.size)
            downloader.download(asset, target) { written, total ->
                if (attempt == id) {
                    _state.update { it.copy(written = written, total = total) }
                    progress(_state.value)
                }
            }
            currentCoroutineContext().ensureActive()
            if (attempt != id) return@withContext
            failure = UpdateDownloadFailure.Verification
            _state.update { it.copy(phase = UpdateDownloadPhase.Verifying) }
            files.validate(target, release)
            failure = UpdateDownloadFailure.Storage
            _state.update { it.copy(phase = UpdateDownloadPhase.Saving) }
            val saved = files.save(target, release)
            val ready = files.ready()
            ready.delete()
            if (!target.renameTo(ready)) { target.copyTo(ready, overwrite = true); target.delete() }
            files.recordReady(release, saved)
            _state.update { it.copy(phase = UpdateDownloadPhase.Ready, savedUri = saved.toString()) }
        } catch (cancellation: CancellationException) {
            if (attempt == id) _state.update { it.copy(phase = UpdateDownloadPhase.Failed, failure = UpdateDownloadFailure.Transfer) }
            throw cancellation
        } catch (_: Exception) {
            if (attempt == id) _state.update { it.copy(phase = UpdateDownloadPhase.Failed, failure = failure) }
        } finally {
            temporary?.delete()
        }
    }

    fun cancel() {
        attempt++
        worker?.cancel()
        if (_state.value.busy) _state.value = UpdateDownloadState()
    }

    fun installerFile(): File? = files.ready().takeIf { _state.value.phase == UpdateDownloadPhase.Ready && it.isFile }
}
