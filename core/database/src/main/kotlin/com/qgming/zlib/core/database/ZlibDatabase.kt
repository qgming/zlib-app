package com.qgming.zlib.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download_queue WHERE state IN ('QUEUED','RESOLVING','DOWNLOADING') ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextActive(): DownloadEntity?

    @Query("SELECT * FROM download_queue WHERE id = :id")
    suspend fun byId(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)

    @Query(
        "UPDATE download_queue SET state = :state, bytesWritten = :written, totalBytes = :total, " +
            "error = :error, updatedAt = :updated WHERE id = :id",
    )
    suspend fun update(id: String, state: String, written: Long, total: Long?, error: String?, updated: Long)

    @Query("UPDATE download_queue SET state = :state, error = :error, updatedAt = :updated WHERE id = :id")
    suspend fun updateState(id: String, state: String, error: String?, updated: Long)

    @Query("UPDATE download_queue SET bytesWritten = 0 WHERE id = :id")
    suspend fun resetProgress(id: String)

    @Query("DELETE FROM download_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE download_queue SET state = 'QUEUED' WHERE state IN ('RESOLVING','DOWNLOADING')")
    suspend fun resetInterrupted()

    @Query("UPDATE download_queue SET state = 'FAILED', error = 'invalid' WHERE state = 'COMPLETED' AND bytesWritten < 1024")
    suspend fun invalidateTinyCompleted()

    @Query("SELECT * FROM download_queue WHERE id = :id")
    fun observe(id: String): Flow<DownloadEntity?>
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE localUri IS NOT NULL ORDER BY downloadedAt DESC")
    fun observeDownloaded(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun byId(id: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET localUri = :uri, downloadedAt = :downloadedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setLocalFile(id: String, uri: String?, downloadedAt: Long?, updatedAt: Long)

    @Query("UPDATE books SET localUri = NULL, downloadedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearLocalFile(id: String, updatedAt: Long)

    @Query("UPDATE books SET localUri = NULL, downloadedAt = NULL, updatedAt = :updatedAt WHERE localUri = :uri")
    suspend fun clearLocalFileByUri(uri: String, updatedAt: Long)

    @Query("SELECT * FROM books WHERE favorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<BookEntity>>
}

@Database(
    entities = [BookEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ZlibDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun bookDao(): BookDao

    companion object {
        const val NAME = "zlib.db"
    }
}
