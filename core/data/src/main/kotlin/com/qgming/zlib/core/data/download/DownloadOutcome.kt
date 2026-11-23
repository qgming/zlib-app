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
    const val UNKNOWN = "unknown"

    fun http(code: Int): String = "http_$code"
}

internal sealed interface DownloadOutcome {
    data class Completed(val uri: String, val totalBytes: Long) : DownloadOutcome
    data class Failed(val error: AppError, val code: String) : DownloadOutcome
}
