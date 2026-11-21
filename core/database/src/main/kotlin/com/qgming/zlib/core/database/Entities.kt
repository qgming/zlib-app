package com.qgming.zlib.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val hash: String,
    val title: String,
    val subtitle: String,
    val authors: String,
    val publisher: String,
    val year: String,
    val language: String,
    val extension: String,
    val sizeBytes: Long?,
    val isbn10: String,
    val isbn13: String,
    val coverUrl: String,
    val description: String,
    val categories: String,
    val edition: String,
    val pages: Int?,
    val rating: Double?,
    val localUri: String?,
    val favorite: Boolean = false,
    val downloadedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "download_queue")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val hash: String,
    val title: String,
    val authors: String,
    val extension: String,
    val coverUrl: String,
    val fileName: String,
    val state: String,
    val bytesWritten: Long,
    val totalBytes: Long?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

object DownloadStates {
    const val QUEUED = "QUEUED"
    const val RESOLVING = "RESOLVING"
    const val DOWNLOADING = "DOWNLOADING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"

    val active = listOf(QUEUED, RESOLVING, DOWNLOADING)
}
