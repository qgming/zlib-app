package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiEndpoints
import com.qgming.zlib.core.network.EapiHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One download attempt for one `id:hash` reference.
 *
 * Temporary-file identity is derived from the complete reference, resume is accepted only for a
 * verified single-segment 206, and completion is decided by the bytes actually written against the
 * transport-declared length (ADR-060). Metadata `filesize` is display-only.
 */
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

    internal suspend fun download(
        item: DownloadEntity,
        session: Session? = sessionManager.current(),
        onProgress: suspend (written: Long, total: Long?) -> Unit,
    ): DownloadOutcome = withContext(dispatchers.io) {
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
    ): DownloadOutcome {
        if (session == null) return failed(AppError.NotLoggedIn, DownloadCodes.SESSION)
        coroutineContext.ensureActive()
        directoryStore.ensureLoaded()
        val tree = directoryStore.tree()
            ?: return failed(
                AppError.Storage(IOException(appText(TextR.string.text_download_folder_unavailable_select_and_authorize_it_again_in_settings))),
                DownloadCodes.STORAGE_PERMISSION,
            )

        val partName = DownloadFileNames.partName(item.id)
        val existing = tree.findFile(partName)
        if (existing != null && !existing.isFile) {
            return failed(
                AppError.Storage(IOException(appText(TextR.string.text_the_temporary_file_location_is_occupied_check_the_download_folder))),
                DownloadCodes.STORAGE,
            )
        }
        val part = existing ?: try {
            tree.createFile(MIME, partName)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return failed(AppError.Storage(t), storageCode(t))
        } ?: return failed(
            AppError.Storage(IOException(appText(TextR.string.text_cannot_create_a_temporary_download_file_check_folder_permissions))),
            DownloadCodes.STORAGE_PERMISSION,
        )

        // Unknown/negative statSize must restart from zero; never emit a negative Range.
        val offset = DownloadRange.resumeOffset(readStatSize(part))
        val partPrefix = if (offset > 0) {
            readPrefix(part) ?: return failed(
                AppError.Storage(IOException(appText(TextR.string.text_cannot_read_the_partial_download_check_folder_permissions))),
                DownloadCodes.STORAGE_PERMISSION,
            )
        } else {
            ByteArray(0)
        }

        val response = when (val opened = openFileResponse(item, offset, session)) {
            is AppResult.Failure -> return failed(opened.error, codeFor(opened.error, DownloadCodes.INVALID))
            is AppResult.Success -> opened.value
        }

        response.use { resp ->
            SafeLog.d(
                "Download",
                "response code=${resp.code} type=${resp.header("Content-Type")} " +
                    "length=${resp.body.contentLength()} host=${resp.request.url.host}",
            )
            if (resp.code == 416) {
                // A stale or already-complete part cannot be validated by size; restart from zero.
                runCatching { part.delete() }
                return failed(
                    AppError.Server(416, appText(TextR.string.text_the_partial_file_is_no_longer_valid_the_download_will_restart)),
                    DownloadCodes.RANGE,
                )
            }
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                return failed(httpError(resp.code, body), httpCode(resp.code, body))
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html")) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                val lower = body.lowercase()
                return if (lower.contains("daily limit") || lower.contains("daily download")) {
                    failed(AppError.Quota(null, appText(TextR.string.text_daily_download_quota_exhausted)), DownloadCodes.QUOTA)
                } else {
                    failed(
                        AppError.Server(resp.code, appText(TextR.string.text_the_download_returned_a_web_page_switch_domains_and_retry)),
                        DownloadCodes.INVALID,
                    )
                }
            }

            val suggestedName = DownloadFileNames.finalName(
                contentDisposition = resp.header("Content-Disposition"),
                urlSegment = resp.request.url.pathSegments.lastOrNull(),
                fallback = item.fileName,
                extension = item.extension,
            )
            val contentExtension = suggestedName.substringAfterLast('.', item.extension)

            val check = DownloadRange.evaluate(
                status = resp.code,
                contentRangeHeader = resp.header("Content-Range"),
                contentLength = resp.body.contentLength(),
                requestedOffset = offset,
                contentType = contentType,
            )
            if (check is DownloadCheck.Invalid) {
                return failed(AppError.Parse(check.reason), DownloadCodes.RANGE)
            }
            val append = offset > 0 && check is DownloadCheck.Append
            if (append && !looksLikeBook(partPrefix, contentExtension)) {
                runCatching { part.delete() }
                return failed(AppError.Parse(appText(TextR.string.text_the_damaged_partial_file_was_removed_please_retry)), DownloadCodes.INVALID)
            }
            if (!append) {
                val prefix = runCatching { resp.peekBody(8).bytes() }.getOrDefault(ByteArray(0))
                if (!looksLikeBook(prefix, contentExtension)) {
                    return failed(
                        AppError.Server(resp.code, appText(TextR.string.text_the_download_is_not_a_valid_book_file)),
                        DownloadCodes.INVALID,
                    )
                }
            }

            val expectedTotal = when (check) {
                is DownloadCheck.Restart -> check.total
                is DownloadCheck.Append -> check.range.total
                is DownloadCheck.Invalid -> null
            }
            val expectedSegment = when (check) {
                is DownloadCheck.Restart -> check.total
                is DownloadCheck.Append -> check.range.length
                is DownloadCheck.Invalid -> null
            }
            val displayTotal = expectedTotal ?: item.totalBytes
            val progressBase = if (append) offset else 0L
            SafeLog.d("Download", "resume offset=$offset code=${resp.code} append=$append")
            // A restart clears stale progress from an earlier part before the first chunk lands.
            onProgress(progressBase, displayTotal)

            val output = try {
                context.contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return failed(AppError.Storage(t), storageCode(t))
            } ?: return failed(
                AppError.Storage(IOException(appText(TextR.string.text_cannot_write_the_temporary_file_check_folder_permissions))),
                DownloadCodes.STORAGE_PERMISSION,
            )

            var sessionWritten = 0L
            var lastReport = 0L
            try {
                output.use { sink ->
                    resp.body.byteStream().use { input ->
                        sessionWritten = StreamCopy.copy(input, sink, expectedSegment) { copied ->
                            val written = progressBase + copied
                            if (written - lastReport >= PROGRESS_STEP) {
                                lastReport = written
                                onProgress(written, displayTotal)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (incomplete: DownloadIncompleteException) {
                return failed(
                    AppError.Parse(appText(TextR.string.text_download_incomplete_progress_is_saved_retry_to_resume)),
                    DownloadCodes.INCOMPLETE,
                )
            } catch (empty: DownloadEmptyException) {
                return failed(AppError.Parse(appText(TextR.string.text_the_download_is_empty_please_retry)), DownloadCodes.INCOMPLETE)
            } catch (read: DownloadReadException) {
                coroutineContext.ensureActive()
                return failed(AppError.Network(read), DownloadCodes.NETWORK)
            } catch (write: DownloadWriteException) {
                return failed(AppError.Storage(write), DownloadCodes.STORAGE)
            } catch (io: IOException) {
                // Closing the SAF sink can surface here; treat it as a local write failure.
                return failed(AppError.Storage(io), storageCode(io))
            } catch (t: Throwable) {
                return failed(AppError.Storage(t), storageCode(t))
            }

            coroutineContext.ensureActive()
            onProgress(progressBase + sessionWritten, displayTotal)

            when (val verdict = DownloadRange.completion(check, offset, sessionWritten)) {
                CompletionCheck.Complete -> Unit
                is CompletionCheck.Short -> return failed(
                    AppError.Parse(appText(TextR.string.text_download_incomplete_progress_is_saved_retry_to_resume)),
                    DownloadCodes.INCOMPLETE,
                )

                is CompletionCheck.UnknownTotal -> return failed(
                    AppError.Parse(appText(TextR.string.text_cannot_verify_download_completeness_please_retry)),
                    DownloadCodes.INCOMPLETE,
                )

                is CompletionCheck.Mismatch -> return failed(
                    AppError.Parse(appText(TextR.string.text_download_size_does_not_match_the_response_please_retry)),
                    DownloadCodes.INVALID,
                )
            }

            val total = expectedTotal ?: (progressBase + sessionWritten)
            val actualSize = readStatSize(part)?.takeIf { it >= 0 }
            if (actualSize != null && actualSize != total) {
                return failed(AppError.Parse(appText(TextR.string.text_actual_file_size_does_not_match_the_download_please_retry)), DownloadCodes.INVALID)
            }
            return finalize(tree, part, item, suggestedName, total)
        }
    }

    private suspend fun finalize(
        tree: DocumentFile,
        part: DocumentFile,
        item: DownloadEntity,
        suggestedName: String,
        total: Long,
    ): DownloadOutcome {
        val finalName = DownloadFileNames.uniqueFinalName(suggestedName, item.bookId) { candidate ->
            tree.findFile(candidate) != null
        }
        val renamed = runCatching { part.renameTo(finalName) }.getOrDefault(false)
        SafeLog.d("Download", "finalized renamed=$renamed")
        if (renamed) {
            // Some providers change the document id on rename; resolve the final document instead
            // of trusting the old part URI.
            val saved = runCatching { tree.findFile(finalName) }.getOrNull()
            return DownloadOutcome.Completed(saved?.uri?.toString() ?: part.uri.toString(), total)
        }
        return copyToFinal(tree, part, finalName, total)
    }

    private suspend fun copyToFinal(
        tree: DocumentFile,
        part: DocumentFile,
        finalName: String,
        total: Long,
    ): DownloadOutcome {
        val target = try {
            tree.createFile(MIME, finalName)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return failed(AppError.Storage(t), storageCode(t))
        } ?: return failed(
            AppError.Storage(IOException(appText(TextR.string.text_cannot_create_the_destination_file_check_folder_permissions))),
            DownloadCodes.STORAGE_PERMISSION,
        )

        try {
            val input = context.contentResolver.openInputStream(part.uri)
                ?: throw IOException(appText(TextR.string.text_cannot_read_the_temporary_file_check_folder_permissions))
            input.use { source ->
                val output = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: throw IOException(appText(TextR.string.text_cannot_write_the_destination_file_check_folder_permissions))
                output.use { sink ->
                    val copied = StreamCopy.copy(source, sink, total)
                    if (copied != total) throw DownloadIncompleteException(total, copied)
                }
            }
        } catch (cancelled: CancellationException) {
            // The attempt's target is incomplete; the part stays for a later resume.
            withContext(NonCancellable) { runCatching { target.delete() } }
            throw cancelled
        } catch (t: Throwable) {
            runCatching { target.delete() }
            return failed(
                if (t is DownloadIncompleteException) {
                    AppError.Parse(appText(TextR.string.text_copy_incomplete_the_temporary_file_is_retained_please_retry))
                } else {
                    AppError.Storage(t)
                },
                if (t is DownloadIncompleteException) DownloadCodes.STORAGE else storageCode(t),
            )
        }

        runCatching { part.delete() }
            .onFailure { SafeLog.w("Download", "cannot remove temporary file after copy") }
        return DownloadOutcome.Completed(target.uri.toString(), total)
    }

    private suspend fun openFileResponse(
        item: DownloadEntity,
        offset: Long,
        session: Session,
    ): AppResult<Response> {
        val first = try {
            execute(fileRequest(item, session, offset, overrideUrl = null))
        } catch (io: IOException) {
            coroutineContext.ensureActive()
            return AppResult.Failure(AppError.Network(io))
        }

        val type = first.header("Content-Type").orEmpty().lowercase()
        if (!type.contains("json")) return AppResult.Success(first)

        val body = runCatching { first.body.string() }.getOrDefault("")
        first.close()
        val link = DownloadLinkParser.extract(body)
            ?: return AppResult.Failure(jsonError(body))
        val url = if (link.startsWith("//")) {
            "https:$link"
        } else if (link.startsWith("http")) {
            link
        } else {
            "https://${session.domain}${if (link.startsWith("/")) link else "/$link"}"
        }
        if (!url.startsWith("https://")) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_the_download_url_does_not_use_https)))
        }
        return try {
            AppResult.Success(execute(fileRequest(item, session, offset, url)))
        } catch (io: IOException) {
            coroutineContext.ensureActive()
            AppResult.Failure(AppError.Network(io))
        }
    }

    private suspend fun execute(request: Request): Response {
        val call = client.newCall(request)
        activeCall = call
        // If cancellation raced with call creation it is observed here; otherwise
        // cancelActiveTransfer cancels the published call, including a blocking socket read.
        coroutineContext.ensureActive()
        return call.execute()
    }

    private fun httpError(code: Int, body: String): AppError {
        val lower = body.lowercase()
        return when {
            lower.contains("daily limit") || lower.contains("daily download") ->
                AppError.Quota(null, appText(TextR.string.text_daily_download_quota_exhausted))

            code == 401 || code == 403 -> AppError.SessionExpired
            lower.contains("please login") || lower.contains("invalid credentials") ->
                AppError.SessionExpired
            code == 513 || code == 517 -> AppError.DomainBlocked(DomainStatus.DIAMWALL)
            code == 502 || code == 503 || code == 504 ->
                AppError.Server(code, appText(TextR.string.text_service_temporarily_unavailable_http, code))

            else -> AppError.Server(code, appText(TextR.string.text_download_failed_http, code))
        }
    }

    private fun httpCode(code: Int, body: String): String {
        val lower = body.lowercase()
        return when {
            lower.contains("daily limit") || lower.contains("daily download") -> DownloadCodes.QUOTA
            code == 401 || code == 403 -> DownloadCodes.SESSION
            lower.contains("please login") || lower.contains("invalid credentials") -> DownloadCodes.SESSION
            code == 513 || code == 517 -> DownloadCodes.DOMAIN
            else -> DownloadCodes.http(code)
        }
    }

    private fun codeFor(error: AppError, fallback: String): String = when (error) {
        is AppError.Quota -> DownloadCodes.QUOTA
        AppError.SessionExpired, AppError.NotLoggedIn -> DownloadCodes.SESSION
        is AppError.DomainBlocked -> DownloadCodes.DOMAIN
        is AppError.Network -> DownloadCodes.NETWORK
        is AppError.Storage -> storageCode(error.cause)
        is AppError.Server -> if (error.code in 200..299) fallback else DownloadCodes.http(error.code)
        else -> fallback
    }

    private fun storageCode(t: Throwable): String =
        if (t is SecurityException || t.cause is SecurityException) {
            DownloadCodes.STORAGE_PERMISSION
        } else {
            DownloadCodes.STORAGE
        }

    private fun jsonError(body: String): AppError {
        val lower = body.lowercase()
        return when {
            lower.contains("daily limit") || lower.contains("daily download") ->
                AppError.Quota(null, appText(TextR.string.text_daily_download_quota_exhausted))

            lower.contains("please login") || lower.contains("invalid credentials") ->
                AppError.SessionExpired

            else -> AppError.Server(200, appText(TextR.string.text_no_download_url_was_returned_switch_domains_and_retry))
        }
    }

    private fun looksLikeBook(prefix: ByteArray, extension: String): Boolean = when (extension.lowercase()) {
        "epub", "docx" -> prefix.size >= 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()
        "pdf" -> prefix.size >= 4 && String(prefix, 0, 4, Charsets.US_ASCII) == "%PDF"
        "fb2" -> prefix.firstOrNull { !it.toInt().toChar().isWhitespace() } == '<'.code.toByte()
        else -> true
    }

    private fun readStatSize(part: DocumentFile): Long? = runCatching {
        context.contentResolver.openFileDescriptor(part.uri, "r")?.use { it.statSize }
    }.getOrNull()

    private fun readPrefix(part: DocumentFile): ByteArray? = try {
        context.contentResolver.openInputStream(part.uri)?.use { input ->
            val buffer = ByteArray(8)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (t: Throwable) {
        null
    }

    private fun fileRequest(
        item: DownloadEntity,
        session: Session,
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
            .apply { DownloadRange.requestHeader(offset)?.let { header("Range", it) } }
            .build()
    }

    private fun failed(error: AppError, code: String): DownloadOutcome =
        DownloadOutcome.Failed(error, code)

    companion object {
        private const val MIME = "application/octet-stream"
        private const val PROGRESS_STEP = 512 * 1024L
    }
}
