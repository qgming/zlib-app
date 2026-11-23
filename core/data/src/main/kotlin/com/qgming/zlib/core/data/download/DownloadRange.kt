package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import java.util.Locale

/** A single validated Content-Range segment with an optional declared total. */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?) {
    val length: Long get() = end - start + 1
}

/** What the response allows us to do with the existing `.part` file. */
internal sealed interface DownloadCheck {
    /** HTTP 200: the server ignored Range; the body starts at byte 0 and replaces the part. */
    data class Restart(val total: Long?) : DownloadCheck

    /** HTTP 206: the body continues exactly at the requested offset. */
    data class Append(val range: ContentRange) : DownloadCheck

    data class Invalid(val reason: String) : DownloadCheck
}

/** Result of checking the transferred bytes against the declared response length. */
internal sealed interface CompletionCheck {
    data object Complete : CompletionCheck
    data class Short(val expected: Long, val actual: Long) : CompletionCheck
    data class UnknownTotal(val written: Long) : CompletionCheck
    data class Mismatch(val expected: Long, val actual: Long) : CompletionCheck
}

/**
 * Pure Range/Content-Range validation. Metadata `filesize` is never used here: completion is
 * decided only by what the transport declared and what was actually written (ADR-060).
 */
internal object DownloadRange {
    private val ContentRangePattern = Regex(
        """^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** `statSize` may be null or negative on some providers; never produce a negative Range. */
    fun resumeOffset(statSize: Long?): Long = statSize?.takeIf { it > 0 } ?: 0L

    fun requestHeader(offset: Long): String? = if (offset > 0) "bytes=$offset-" else null

    fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = ContentRangePattern.matchEntire(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end == Long.MAX_VALUE) return null
        if (start < 0 || end < start) return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        if (total != null && total < end + 1) return null
        return ContentRange(start, end, total)
    }

    fun evaluate(
        status: Int,
        contentRangeHeader: String?,
        contentLength: Long,
        requestedOffset: Long,
        contentType: String? = null,
    ): DownloadCheck {
        val offset = resumeOffset(requestedOffset)
        if (contentType?.lowercase(Locale.US)?.contains("multipart/byteranges") == true) {
            return DownloadCheck.Invalid(appText(TextR.string.text_the_server_returned_multiple_ranges_cannot_resume_safely))
        }
        return when (status) {
            200 -> DownloadCheck.Restart(contentLength.takeIf { it > 0 })

            206 -> {
                val range = parseContentRange(contentRangeHeader)
                    ?: return DownloadCheck.Invalid(appText(TextR.string.text_the_resume_response_has_no_valid_content_range))
                if (range.start != offset) {
                    return DownloadCheck.Invalid(appText(TextR.string.text_resume_offset_does_not_match_the_request))
                }
                if (contentLength >= 0 && contentLength != range.length) {
                    return DownloadCheck.Invalid(appText(TextR.string.text_resume_response_length_does_not_match_content_range))
                }
                DownloadCheck.Append(range)
            }

            else -> DownloadCheck.Invalid(appText(TextR.string.text_unsupported_download_response_http, status))
        }
    }

    /**
     * [startOffset] is only meaningful for 206; a 200 response always restarts at byte 0.
     * Completion requires a transport-declared total and exactly that many bytes.
     */
    fun completion(check: DownloadCheck, startOffset: Long, sessionWritten: Long): CompletionCheck {
        val start = resumeOffset(startOffset)
        return when (check) {
            is DownloadCheck.Invalid -> CompletionCheck.Mismatch(0L, sessionWritten)

            is DownloadCheck.Restart -> {
                val expected = check.total
                when {
                    expected == null -> CompletionCheck.UnknownTotal(sessionWritten)
                    sessionWritten < expected -> CompletionCheck.Short(expected, sessionWritten)
                    sessionWritten > expected -> CompletionCheck.Mismatch(expected, sessionWritten)
                    else -> CompletionCheck.Complete
                }
            }

            is DownloadCheck.Append -> {
                val expectedSegment = check.range.length
                when {
                    sessionWritten < expectedSegment ->
                        CompletionCheck.Short(expectedSegment, sessionWritten)

                    sessionWritten > expectedSegment ->
                        CompletionCheck.Mismatch(expectedSegment, sessionWritten)

                    else -> {
                        val finalBytes = start + expectedSegment
                        val total = check.range.total
                        when {
                            total == null -> CompletionCheck.UnknownTotal(finalBytes)
                            finalBytes < total -> CompletionCheck.Short(total, finalBytes)
                            finalBytes > total -> CompletionCheck.Mismatch(total, finalBytes)
                            else -> CompletionCheck.Complete
                        }
                    }
                }
            }
        }
    }
}
