package com.qgming.zlib.core.model

/** Search filters accepted by `/eapi/book/search`. Page is supplied separately. */
data class SearchQuery(
    val message: String,
    val languages: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val exact: Boolean = false,
    val order: String? = null,
) {
    val canSearch: Boolean get() = message.isNotBlank()
}

enum class DownloadState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED,
}

data class DownloadItem(
    val id: String,
    val bookId: String,
    val hash: String,
    val fileName: String,
    val state: DownloadState,
    val bytesWritten: Long,
    val totalBytes: Long?,
    val error: String? = null,
    val title: String = "",
    val authors: String = "",
    val coverUrl: String = "",
    val extension: String = "",
) {
    val progress: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0) return null
            return (bytesWritten.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}
