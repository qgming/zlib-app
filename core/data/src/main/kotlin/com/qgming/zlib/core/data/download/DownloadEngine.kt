package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainVerificationCookies
import com.qgming.zlib.core.network.EapiEndpoints
import com.qgming.zlib.core.network.EapiHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val allowedHosts: AllowedHostRegistry,
    private val journal: DownloadJournal,
    private val verificationCookies: DomainVerificationCookies,
) {
    private val initialClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val fileClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        coroutineContext.ensureActive()
        val treeUri = directoryStore.ensureLoaded()?.toString()
            ?: return failed(AppError.Storage(IOException()), DownloadCodes.STORAGE_PERMISSION)
        val tree = directoryStore.tree()
            ?: return failed(
                AppError.Storage(IOException(appText(TextR.string.text_download_folder_unavailable_select_and_authorize_it_again_in_settings))),
                DownloadCodes.STORAGE_PERMISSION,
            )

        val publication = publisher(treeUri)
        journal.pending(treeUri, item.id, item.createdAt)?.let { pending ->
            return DownloadOutcome.Completed(publication.publish(pending))
        }
        if (session == null) return failed(AppError.NotLoggedIn, DownloadCodes.SESSION)

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

        val partUri = part.uri.toString()
        val representation = journal.continuation(treeUri, item.id, partUri)
        val offset = DownloadRange.resumeOffset(readStatSize(part)).takeIf {
            representation != null && it > 0 && readPrefix(part)?.let { bytes -> looksLikeBook(bytes, item.extension) } == true
        } ?: 0L
        val resume = representation?.takeIf { offset > 0 }?.let { ResumeDownload(offset, it) }

        val opened = when (val opened = openFileResponse(item, resume, session)) {
            is AppResult.Failure -> return failed(opened.error, codeFor(opened.error, DownloadCodes.INVALID))
            is AppResult.Success -> opened.value
        }

        opened.response.use { resp ->
            SafeLog.d(
                "Download",
                "response code=${resp.code} type=${resp.header("Content-Type")} " +
                    "length=${resp.body.contentLength()} host=${resp.request.url.host}",
            )
            if (resp.code == 416) {
                journal.setContinuation(treeUri, item.id, null)
                return failed(
                    AppError.Server(416, appText(TextR.string.text_the_partial_file_is_no_longer_valid_the_download_will_restart)),
                    DownloadCodes.RANGE,
                )
            }
            if (!resp.isSuccessful) {
                val error = downloadHttpError(resp.code, eapi = opened.eapi, body = errorText(resp), diamwall = resp.isDiamwall())
                return failed(error, codeFor(error, DownloadCodes.INVALID))
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("json")) {
                return failed(AppError.Parse(appText(TextR.string.text_the_download_returned_a_web_page_switch_domains_and_retry)), DownloadCodes.INVALID)
            }
            if (resp.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
                return failed(AppError.Parse(appText(TextR.string.text_the_download_is_not_a_valid_book_file)), DownloadCodes.INVALID)
            }

            val suggestedName = DownloadFileNames.finalName(
                contentDisposition = resp.header("Content-Disposition"),
                urlSegment = resp.request.url.pathSegments.lastOrNull(),
                fallback = item.fileName,
                extension = item.extension,
            )

            val check = DownloadRange.evaluate(
                status = resp.code,
                contentRangeHeader = resp.header("Content-Range"),
                contentLength = resp.body.contentLength(),
                requestedOffset = opened.offset,
                contentType = contentType,
            )
            if (check is DownloadCheck.Invalid) {
                return failed(AppError.Parse(check.reason), DownloadCodes.RANGE)
            }
            val append = opened.offset > 0 && check is DownloadCheck.Append
            if (!append && resp.code != 200) {
                return failed(AppError.Parse(appText(TextR.string.text_the_resume_position_is_invalid_retry_the_download)), DownloadCodes.RANGE)
            }
            if (!append) {
                val prefix = try {
                    resp.peekBody(8).bytes()
                } catch (io: IOException) {
                    coroutineContext.ensureActive()
                    return failed(AppError.Network(IOException("Could not read download body")), DownloadCodes.NETWORK)
                }
                if (!looksLikeBook(prefix, item.extension)) {
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
            val progressBase = if (append) opened.offset else 0L
            SafeLog.d("Download", "resume offset=$progressBase code=${resp.code} append=$append")
            onProgress(progressBase, displayTotal)
            if (!append) journal.setContinuation(treeUri, item.id, null)

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
                    if (!append && readStatSize(part) == 0L) {
                        DownloadRepresentation.from(resp.request.url, resp.header("ETag"))?.let {
                            journal.setContinuation(treeUri, item.id, DownloadContinuation(partUri, it))
                        }
                    }
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

            when (DownloadRange.completion(check, opened.offset, sessionWritten)) {
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
            val sha256 = fileSha256(publicationFiles(treeUri).read(partUri), total)
                ?: return failed(AppError.Parse(appText(TextR.string.text_actual_file_size_does_not_match_the_download_please_retry)), DownloadCodes.INVALID)
            val targetName = publication.availableName(suggestedName)
            val pending = PendingDownloadPublication(item.id, item.createdAt, treeUri, partUri, targetName, total, sha256)
            journal.update(pending)
            return DownloadOutcome.Completed(publication.publish(pending, sourceVerified = true))
        }
    }

    private suspend fun openFileResponse(
        item: DownloadEntity,
        resume: ResumeDownload?,
        session: Session,
    ): AppResult<FileResponse> {
        val origin = "https://${session.domain}".toHttpUrlOrNull()
            ?.takeIf { DomainPolicy.isStandardHttps(it) && it.host == session.domain && allowedHosts.isAllowed(it.host) }
            ?: return AppResult.Failure(AppError.NoDomain)
        if (!BOOK_ID.matches(item.bookId) || !BOOK_HASH.matches(item.hash)) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_request_parameters)))
        }
        val endpoint = checkNotNull(origin.resolve(EapiEndpoints.file(item.bookId, item.hash)))
        return try {
            val first = executeFileRequest(endpoint, resume, session)
            val response = first.response
            if (response.isDiamwall()) {
                response.close()
                return AppResult.Failure(AppError.DomainBlocked(DomainStatus.DIAMWALL))
            }
            if (response.isRedirect) {
                val target = response.use { safeLink(it.request.url, it.header("Location")) }
                    ?: return invalidLink()
                return openAnonymousFile(target, resume)
            }
            if (response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)) {
                val body = response.use { errorText(it) }
                val link = if (response.code == 200) DownloadLinkParser.extract(body) else null
                if (link == null) return AppResult.Failure(downloadHttpError(response.code, eapi = true, body = body))
                val target = safeLink(response.request.url, link) ?: return invalidLink()
                return openAnonymousFile(target, resume)
            }
            AppResult.Success(first)
        } catch (io: IOException) {
            coroutineContext.ensureActive()
            AppResult.Failure(AppError.Network(IOException(appText(TextR.string.text_network_error))))
        }
    }

    private suspend fun openAnonymousFile(initial: HttpUrl, resume: ResumeDownload?): AppResult<FileResponse> {
        var next = initial
        repeat(MAX_FILE_REDIRECTS + 1) {
            val opened = executeFileRequest(next, resume)
            if (!opened.response.isRedirect) return AppResult.Success(opened)
            next = opened.response.use { safeLink(it.request.url, it.header("Location")) } ?: return invalidLink()
        }
        return invalidLink()
    }

    private suspend fun executeFileRequest(url: HttpUrl, resume: ResumeDownload?, session: Session? = null): FileResponse {
        val applicable = resume?.takeIf { it.representation.matchesUrl(url) }
        val transport = if (session == null) fileClient else initialClient
        val response = execute(transport, fileRequest(url, session, applicable))
        if (response.code == 206 && applicable != null &&
            !applicable.representation.matchesResponse(response.request.url, response.header("ETag"))
        ) {
            response.close()
            return FileResponse(execute(transport, fileRequest(url, session, null)), 0, session != null)
        }
        return FileResponse(response, applicable?.offset ?: 0, session != null)
    }

    private suspend fun execute(transport: OkHttpClient, request: Request): Response {
        val call = transport.newCall(request)
        activeCall = call
        // If cancellation raced with call creation it is observed here; otherwise
        // cancelActiveTransfer cancels the published call, including a blocking socket read.
        coroutineContext.ensureActive()
        return call.execute()
    }

    private suspend fun errorText(response: Response): String = try {
        val bytes = response.peekBody(MAX_PROTOCOL_BYTES + 1).bytes()
        if (bytes.size > MAX_PROTOCOL_BYTES) "" else bytes.toString(Charsets.UTF_8)
    } catch (_: IOException) {
        coroutineContext.ensureActive()
        ""
    }

    private fun Response.isDiamwall(): Boolean = code in listOf(513, 517) ||
        header("Server")?.contains("DiamWall", ignoreCase = true) == true ||
        headers("Set-Cookie").any { it.startsWith("__diamwall=", ignoreCase = true) }

    private fun safeLink(base: HttpUrl, address: String?): HttpUrl? = address
        ?.takeIf(String::isNotBlank)?.let(base::resolve)?.takeIf(DomainPolicy::isStandardHttps)

    private fun invalidLink() = AppResult.Failure(AppError.Parse(appText(TextR.string.text_the_download_url_does_not_use_https)))

    internal suspend fun recover(pending: PendingDownloadPublication): DownloadOutcome.Completed? =
        publisher(pending.treeUri).recover(pending)?.let { DownloadOutcome.Completed(it) }

    internal suspend fun finishPublication(pending: PendingDownloadPublication) {
        try {
            publisher(pending.treeUri).removeOriginalPart(pending)
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            SafeLog.w("Download", "temporary file cleanup unavailable")
        }
        journal.finish(pending)
    }

    internal suspend fun abandonPublication(pending: PendingDownloadPublication): Boolean {
        if (!publisher(pending.treeUri).abandon(pending)) return false
        journal.finish(pending)
        return true
    }

    private fun publisher(treeUri: String) = DownloadPublication(publicationFiles(treeUri), journal::update)

    private fun publicationFiles(treeUri: String) = SafDownloadPublicationFiles(context.contentResolver, treeUri.toUri())

    private fun codeFor(error: AppError, fallback: String): String = when (error) {
        is AppError.Quota -> DownloadCodes.QUOTA
        AppError.SessionExpired, AppError.NotLoggedIn -> DownloadCodes.SESSION
        is AppError.DomainBlocked, AppError.NoDomain -> DownloadCodes.DOMAIN
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

    private fun fileRequest(url: HttpUrl, session: Session?, resume: ResumeDownload?): Request {
        val eapiTarget = session != null && allowedHosts.isAllowed(url.host)
        val verificationCookie = if (eapiTarget) {
            DomainVerificationCookies.sanitizeCookieHeader(verificationCookies.cookieHeaderFor(url))
        } else {
            null
        }
        val userAgent = if (verificationCookie != null) {
            DomainVerificationCookies.sanitizeUserAgent(verificationCookies.userAgentFor(url)) ?: EapiHeaders.USER_AGENT
        } else {
            EapiHeaders.USER_AGENT
        }
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", if (session != null) EapiHeaders.ACCEPT else "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                if (eapiTarget) {
                    val activeSession = checkNotNull(session)
                    val cookies = listOfNotNull(activeSession.cookieHeader, verificationCookie).filter(String::isNotBlank)
                    if (cookies.isNotEmpty()) header("Cookie", cookies.joinToString("; "))
                    header(EapiHeaders.REMIX_USER_ID, activeSession.remixUserId)
                    header(EapiHeaders.REMIX_USER_KEY, activeSession.remixUserKey)
                    header(EapiHeaders.SOURCE, EapiHeaders.SOURCE_ANDROID)
                }
                if (resume != null) {
                    header("Range", checkNotNull(DownloadRange.requestHeader(resume.offset)))
                    header("If-Range", resume.representation.etag)
                }
            }
            .build()
    }

    private fun failed(error: AppError, code: String): DownloadOutcome =
        DownloadOutcome.Failed(error, code)

    companion object {
        private const val MIME = "application/octet-stream"
        private const val PROGRESS_STEP = 512 * 1024L
        private const val MAX_PROTOCOL_BYTES = 64 * 1024L
        private const val MAX_FILE_REDIRECTS = 5
        private val BOOK_ID = Regex("[0-9]{1,32}")
        private val BOOK_HASH = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

private data class ResumeDownload(val offset: Long, val representation: DownloadRepresentation)
private data class FileResponse(val response: Response, val offset: Long, val eapi: Boolean)
