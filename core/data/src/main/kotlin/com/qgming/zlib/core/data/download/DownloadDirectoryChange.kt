package com.qgming.zlib.core.data.download

/** Outcome of changing the download directory; [pausedTasks] were active and are now paused. */
data class DownloadDirectoryChange(
    val directoryName: String?,
    val pausedTasks: Int,
)
