package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText

internal fun downloadHttpError(code: Int, eapi: Boolean, body: String, diamwall: Boolean = false): AppError {
    if (eapi) {
        if (diamwall || code == 513 || code == 517) return AppError.DomainBlocked(DomainStatus.DIAMWALL)
        val message = DownloadLinkParser.failureMessage(body)?.lowercase().orEmpty()
        if (message.contains("daily limit") || message.contains("daily download")) {
            return AppError.Quota(null, appText(TextR.string.text_daily_download_quota_exhausted))
        }
        if (code == 401 || message.contains("please login") || message.contains("invalid credentials")) return AppError.SessionExpired
    }
    return when (code) {
        in 200..299 -> AppError.Parse(appText(TextR.string.text_no_download_url_was_returned_switch_domains_and_retry))
        502, 503, 504 -> AppError.Server(code, appText(TextR.string.text_service_temporarily_unavailable_http, code))
        else -> AppError.Server(code, appText(TextR.string.text_download_failed_http, code))
    }
}
