package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.database.BookEntity
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.DownloadState

fun Book.toEntity(now: Long, localUri: String? = null): BookEntity = BookEntity(
    id = id,
    hash = hash,
    title = title,
    subtitle = subtitle,
    authors = authors,
    publisher = publisher,
    year = year,
    language = language,
    extension = extension,
    sizeBytes = sizeBytes,
    isbn10 = isbn10,
    isbn13 = isbn13,
    coverUrl = coverUrl,
    description = description,
    categories = categories.joinToString(","),
    edition = edition,
    pages = pages,
    rating = rating,
    localUri = localUri,
    favorite = false,
    downloadedAt = if (localUri != null) now else null,
    createdAt = now,
    updatedAt = now,
)

fun BookEntity.toBook(): Book = Book(
    id = id,
    hash = hash,
    title = title,
    subtitle = subtitle,
    authors = authors,
    publisher = publisher,
    year = year,
    language = language,
    extension = extension,
    sizeBytes = sizeBytes,
    isbn10 = isbn10,
    isbn13 = isbn13,
    coverUrl = coverUrl,
    description = description,
    categories = categories.split(',').filter(String::isNotBlank),
    edition = edition,
    pages = pages,
    rating = rating,
    localUri = localUri,
    downloadedAt = downloadedAt,
)

fun DownloadEntity.toItem(): DownloadItem = DownloadItem(
    id = id,
    bookId = bookId,
    hash = hash,
    fileName = fileName,
    state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED),
    bytesWritten = bytesWritten,
    totalBytes = totalBytes,
    error = error,
    title = title,
    authors = authors,
    coverUrl = coverUrl,
    extension = extension,
)
