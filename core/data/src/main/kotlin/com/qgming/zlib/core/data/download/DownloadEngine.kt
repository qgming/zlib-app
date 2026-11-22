package com.qgming.zlib.core.data.download

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.network.EapiEndpoints
import com.qgming.zlib.core.network.EapiHeaders
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val sessionManager: SessionManager,
    private val directoryStore: DownloadDirectoryStore,
    private val dispatchers: AppDispatchers,
) {
    @Volatile private var activeCall: Call? = null

    fun cancelActiveTransfer() { activeCall?.cancel() }

    suspend fun download(
        item: DownloadEntity,
        session: Session? = sessionManager.current(),
        onProgress: suspend (written: Long, total: Long?) -> Unit,
    ): AppResult<String> = withContext(dispatchers.io) {
        try {
            downloadInner(item, session, onProgress)
        } finally {
            activeCall = null
        }
    }

    private suspend fun downloadInner(
        item: DownloadEntity,
        session: Session?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<String> {
        if (session == null) return AppResult.Failure(AppError.NotLoggedIn)
        coroutineContext.ensureActive()
        directoryStore.ensureLoaded()
        val tree = directoryStore.tree()
            ?: return AppResult.Failure(AppError.Storage(IllegalStateException("download directory missing")))

        val part = tree.findFile("${item.fileName}.part")
            ?: tree.createFile(MIME, "${item.fileName}.part")
            ?: return AppResult.Failure(AppError.Storage(IllegalStateException("cannot create .part")))

        val offset = context.contentResolver.openFileDescriptor(part.uri, "r")?.use { it.statSize } ?: 0L
        val partPrefix = if (offset > 0) readPrefix(part) else ByteArray(0)
        val response = when (val opened = openFileResponse(item, offset, session)) {
            is AppResult.Failure -> return opened
            is AppResult.Success -> opened.value
        }

        response.use { resp ->
            SafeLog.d(
                "Download",
                "response code=${resp.code} type=${resp.header("Content-Type")} length=${resp.body.contentLength()} host=${resp.request.url.host}",
            )
            if (resp.code == 416) {
                part.delete()
                return AppResult.Failure(AppError.Server(416, "续传文件已失效，请重试"))
            }
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                val lower = body.lowercase()
                val error = when {
                    lower.contains("daily limit") || lower.contains("daily download") -> AppError.Quota(null, "今日下载额度已用完")
                    resp.code == 401 || resp.code == 403 -> AppError.SessionExpired
                    resp.code == 513 || resp.code == 517 -> AppError.DomainBlocked(DomainStatus.DIAMWALL)
                    resp.code == 502 || resp.code == 503 || resp.code == 504 ->
                        AppError.Server(resp.code, "服务暂时不可用（HTTP ${resp.code}）")

                    else -> AppError.Server(resp.code, "下载失败（HTTP ${resp.code}）")
                }
                return AppResult.Failure(error)
            }

            val append = resp.code == 206 && offset > 0
            if (append && !looksLikeBook(partPrefix, item.extension)) {
                part.delete()
                return AppResult.Failure(AppError.Parse("续传文件已损坏，已清理，请重试"))
            }
            if (!append) {
                val prefix = runCatching { resp.peekBody(8).bytes() }.getOrDefault(ByteArray(0))
                if (!looksLikeBook(prefix, item.extension)) {
                    return AppResult.Failure(AppError.Server(resp.code, "下载内容不是有效图书文件"))
                }
            }
            SafeLog.d("Download", "resume offset=$offset code=${resp.code} append=$append")
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html")) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                return AppResult.Failure(
                    if (body.lowercase().contains("daily limit") || body.lowercase().contains("daily download")) {
                        AppError.Quota(null, "今日下载额度已用完")
                    } else {
                        AppError.Server(resp.code, "下载返回了网页，请切换域名后重试")
                    },
                )
            }
            val startBytes = if (append) offset else 0L
            val length = resp.body.contentLength()
            val total = if (length >= 0) startBytes + length else item.totalBytes

            val out = context.contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt")
                ?: return AppResult.Failure(AppError.Storage(IllegalStateException("cannot open .part")))
            try {
                out.use { output ->
                    resp.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var written = startBytes
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            coroutineContext.ensureActive()
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
            } catch (io: java.io.IOException) {
                coroutineContext.ensureActive()
                return AppResult.Failure(AppError.Network(io))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return AppResult.Failure(AppError.Storage(t))
            }
        }

        coroutineContext.ensureActive()
        val finalName = uniqueName(tree, item.fileName, item.bookId)
        val renamed = runCatching { part.renameTo(finalName) }.getOrDefault(false)
        SafeLog.d("Download", "finished name=$finalName renamed=$renamed")
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

    private suspend fun openFileResponse(item: DownloadEntity, offset: Long, session: Session): AppResult<okhttp3.Response> {
        val first = try {
            execute(fileRequest(item, session, offset, overrideUrl = null))
        } catch (io: java.io.IOException) {
            coroutineContext.ensureActive()
            return AppResult.Failure(AppError.Network(io))
        }

        val type = first.header("Content-Type").orEmpty().lowercase()
        if (!type.contains("json")) return AppResult.Success(first)

        val body = runCatching { first.body.string() }.getOrDefault("")
        first.close()
        val link = DownloadLinkParser.extract(body)
            ?: return AppResult.Failure(jsonError(body))
        val url = if (link.startsWith("//")) "https:$link" else if (link.startsWith("http")) {
            link
        } else {
            "https://${session.domain}${if (link.startsWith("/")) link else "/$link"}"
        }
        if (!url.startsWith("https://")) {
            return AppResult.Failure(AppError.Parse("下载地址不是 HTTPS"))
        }
        return try {
            AppResult.Success(execute(fileRequest(item, session, offset, url)))
        } catch (io: java.io.IOException) {
            coroutineContext.ensureActive()
            AppResult.Failure(AppError.Network(io))
        }
    }

    private suspend fun execute(request: Request): okhttp3.Response {
        val call = client.newCall(request)
        activeCall = call
        // If cancellation raced with call creation it is observed here; otherwise cancelActiveTransfer
        // cancels the published call, including a blocking socket read.
        coroutineContext.ensureActive()
        return call.execute()
    }

    private fun jsonError(body: String): AppError {
        val lower = body.lowercase()
        return when {
            lower.contains("daily limit") || lower.contains("daily download") -> AppError.Quota(null, "今日下载额度已用完")
            lower.contains("please login") || lower.contains("invalid credentials") ->
                AppError.SessionExpired

            else -> AppError.Server(200, "下载接口没有返回文件地址，请切换域名后重试")
        }
    }

    private fun looksLikeBook(prefix: ByteArray, extension: String): Boolean = when (extension.lowercase()) {
        "epub", "docx" -> prefix.size >= 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()
        "pdf" -> prefix.size >= 4 && String(prefix, 0, 4, Charsets.US_ASCII) == "%PDF"
        "fb2" -> prefix.firstOrNull { !it.toInt().toChar().isWhitespace() } == '<'.code.toByte()
        else -> true
    }

    private fun readPrefix(part: DocumentFile): ByteArray = runCatching {
        context.contentResolver.openInputStream(part.uri)?.use { input ->
            val buffer = ByteArray(8)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        } ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))

    private fun fileRequest(
        item: DownloadEntity,
        session: com.qgming.zlib.core.model.Session,
        offset: Long,
        overrideUrl: String?,
    ): Request {
        val url = overrideUrl ?: "https://${session.domain}${EapiEndpoints.file(item.bookId, item.hash)}"
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", EapiHeaders.USER_AGENT)
            .header("Accept", if (overrideUrl == null) EapiHeaders.ACCEPT else "*/*")
            .apply {
                if (url.toHttpUrl().host == session.domain) {
                    header("Cookie", session.cookieHeader)
                    header(EapiHeaders.REMIX_USER_ID, session.remixUserId)
                    header(EapiHeaders.REMIX_USER_KEY, session.remixUserKey)
                }
            }
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
