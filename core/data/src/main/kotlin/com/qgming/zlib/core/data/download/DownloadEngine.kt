package com.qgming.zlib.core.data.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.FileNames
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.network.EapiEndpoints
import com.qgming.zlib.core.network.EapiHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val sessionManager: SessionManager,
    private val directoryStore: DownloadDirectoryStore,
    private val dispatchers: AppDispatchers,
) {
    suspend fun download(
        item: DownloadEntity,
        onProgress: suspend (written: Long, total: Long?) -> Unit,
    ): AppResult<String> = withContext(dispatchers.io) {
        downloadInner(item, onProgress)
    }

    private suspend fun downloadInner(
        item: DownloadEntity,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<String> {
        val session = sessionManager.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        directoryStore.ensureLoaded()
        val tree = directoryStore.tree()
            ?: return AppResult.Failure(AppError.Storage(IllegalStateException("download directory missing")))

        val part = tree.findFile("${item.fileName}.part")
            ?: tree.createFile(MIME, "${item.fileName}.part")
            ?: return AppResult.Failure(AppError.Storage(IllegalStateException("cannot create .part")))

        val offset = context.contentResolver.openFileDescriptor(part.uri, "r")?.use { it.statSize } ?: 0L
        val request = request(item, offset)
            ?: return AppResult.Failure(AppError.NotLoggedIn)

        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (io: java.io.IOException) {
            return AppResult.Failure(AppError.Network(io))
        }

        response.use { resp ->
            if (resp.code == 416) {
                part.delete()
                return AppResult.Failure(AppError.Server(416, "续传文件已失效，请重试"))
            }
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                val lower = body.lowercase()
                val error = when {
                    lower.contains("daily limit") -> AppError.Quota(null, body.take(120))
                    resp.code == 401 || resp.code == 403 -> AppError.SessionExpired
                    resp.code == 513 || resp.code == 517 -> AppError.DomainBlocked(DomainStatus.DIAMWALL)
                    resp.code == 502 || resp.code == 503 || resp.code == 504 ->
                        AppError.Server(resp.code, "服务暂时不可用（HTTP ${resp.code}）")

                    else -> AppError.Server(resp.code, "下载失败（HTTP ${resp.code}）")
                }
                return AppResult.Failure(error)
            }

            val append = resp.code == 206 && offset > 0
            val startBytes = if (append) offset else 0L
            val length = resp.body.contentLength()
            val total = if (length >= 0) startBytes + length else item.totalBytes

            val out = context.contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt")
                ?: return AppResult.Failure(AppError.Storage(IllegalStateException("cannot open .part")))
            out.use { output ->
                resp.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = startBytes
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 512 * 1024) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                    onProgress(written, total)
                }
            }
        }

        val finalName = uniqueName(tree, item.fileName, item.bookId)
        val renamed = runCatching { part.renameTo(finalName) }.getOrDefault(false)
        if (renamed) return AppResult.Success(part.uri.toString())

        val target = tree.createFile(MIME, finalName)
            ?: return AppResult.Failure(AppError.Storage(IllegalStateException("cannot create file")))
        runCatching {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                context.contentResolver.openInputStream(part.uri)?.use { input -> input.copyTo(output) }
            } ?: error("cannot open target")
        }.getOrElse {
            target.delete()
            return AppResult.Failure(AppError.Storage(it))
        }
        part.delete()
        return AppResult.Success(target.uri.toString())
    }

    private fun request(item: DownloadEntity, offset: Long): Request? {
        val session = sessionManager.current() ?: return null
        val url = "https://${session.domain}${EapiEndpoints.file(item.bookId, item.hash)}"
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", EapiHeaders.USER_AGENT)
            .header("Cookie", session.cookieHeader)
            .header(EapiHeaders.REMIX_USER_ID, session.remixUserId)
            .header(EapiHeaders.REMIX_USER_KEY, session.remixUserKey)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
    }

    private fun uniqueName(tree: DocumentFile, fileName: String, bookId: String): String {
        if (tree.findFile(fileName) == null) return fileName
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isBlank()) "$base ($bookId)" else "$base ($bookId).$extension"
    }

    companion object {
        private const val MIME = "application/octet-stream"
    }
}
