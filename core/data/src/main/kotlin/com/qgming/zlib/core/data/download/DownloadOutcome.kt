package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError

/** Persisted and user-visible download failure categories. */
internal object DownloadCodes {
    const val QUOTA = "quota"
    const val SESSION = "session"
    const val DOMAIN = "domain"
    const val NETWORK = "network"
    const val STORAGE = "storage"
    const val STORAGE_PERMISSION = "storage_permission"
    const val RANGE = "range"
    const val INCOMPLETE = "incomplete"
    const val INVALID = "invalid"
    const val FOREGROUND_UNAVAILABLE = "foreground_unavailable"
    const val FOREGROUND_TIMEOUT = "foreground_timeout"
    const val UNKNOWN = "unknown"

    fun http(code: Int): String = "http_$code"
}

internal sealed interface DownloadOutcome {
    data class Completed(val publication: PendingDownloadPublication) : DownloadOutcome {
        val uri: String get() = requireNotNull(publication.targetUri)
        val totalBytes: Long get() = publication.totalBytes
    }
    data class Failed(val error: AppError, val code: String) : DownloadOutcome
}
