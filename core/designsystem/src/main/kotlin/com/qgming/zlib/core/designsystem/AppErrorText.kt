package com.qgming.zlib.core.designsystem

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.DomainStatus

fun DomainStatus.label(): String = when (this) {
    DomainStatus.UNKNOWN -> appText(TextR.string.text_not_checked)
    DomainStatus.PROBING -> appText(TextR.string.text_checking)
    DomainStatus.HEALTHY -> appText(TextR.string.text_available)
    DomainStatus.CHALLENGED -> appText(TextR.string.text_human_verification_required)
    DomainStatus.DIAMWALL -> appText(TextR.string.text_blocked_by_diamwall)
    DomainStatus.REDIRECT_LOOP -> appText(TextR.string.text_redirect_error)
    DomainStatus.HTTP_ERROR -> appText(TextR.string.text_http_error)
    DomainStatus.NETWORK_ERROR -> appText(TextR.string.text_network_unreachable)
    DomainStatus.INVALID -> appText(TextR.string.text_invalid_domain)
}

/**
 * Single place that turns [AppError] into localized, actionable user-facing copy.
 * Feature modules must not invent their own raw error strings.
 */
fun AppError.userMessage(): String = when (this) {
    AppError.NoDomain ->
        appText(TextR.string.text_no_available_domain_check_your_connection_and_retry_or_add_a_mirror_in_s)

    AppError.NotLoggedIn -> appText(TextR.string.text_please_sign_in_first)

    AppError.SessionExpired -> appText(TextR.string.text_your_session_has_expired_please_sign_in_again)

    AppError.SearchRefused -> appText(TextR.string.text_z_library_refused_the_search)

    is AppError.AuthFailed ->
        message?.takeIf { it.isNotBlank() }?.let { appText(TextR.string.text_sign_in_failed_you_can_also_try_importing_a_cookie, it) }
            ?: appText(TextR.string.text_incorrect_email_or_password_you_can_also_try_importing_a_cookie)

    is AppError.Quota -> buildString {
        append(appText(TextR.string.text_daily_download_quota_exhausted))
        if (remaining != null) append(appText(TextR.string.text_remaining, remaining))
        append(". ")
        if (!resetHint.isNullOrBlank()) append(appText(TextR.string.text_server_message, resetHint))
    }

    is AppError.DomainBlocked -> when (status) {
        DomainStatus.DIAMWALL -> appText(TextR.string.text_this_domain_is_blocked_by_diamwall_switch_domains_or_use_a_vpn_proxy)
        DomainStatus.CHALLENGED -> appText(TextR.string.text_this_domain_requires_human_verification_switch_domains)
        DomainStatus.REDIRECT_LOOP -> appText(TextR.string.text_domain_redirect_failed_switch_domains)
        DomainStatus.INVALID -> appText(TextR.string.text_the_domain_is_invalid_or_not_allowed)
        DomainStatus.HTTP_ERROR -> appText(TextR.string.text_the_domain_returned_an_unexpected_response_switch_domains)
        DomainStatus.NETWORK_ERROR -> appText(TextR.string.text_cannot_connect_to_this_domain_check_your_connection)
        else -> appText(TextR.string.text_this_domain_is_unavailable_switch_domains)
    }

    is AppError.Network -> appText(TextR.string.text_connection_failed_check_your_network_or_vpn_and_retry)

    is AppError.Server -> when (code) {
        502, 503, 504 -> appText(TextR.string.text_service_temporarily_unavailable_please_retry_later)
        401, 403 -> appText(TextR.string.text_access_denied_your_session_may_have_expired)
        404 -> appText(TextR.string.text_endpoint_not_found_the_domain_may_be_unavailable_switch_domains)
        else -> if (message.isNotBlank()) appText(TextR.string.text_server_error, message) else appText(TextR.string.text_server_error_http, code)
    }

    is AppError.Storage -> when {
        cause is SecurityException -> appText(TextR.string.text_cannot_access_the_download_folder_select_and_authorize_it_again_in_setti)
        cause is java.io.IOException -> cause.message?.let(com.qgming.zlib.core.common.SafeLog::redactText)
            ?.takeIf { it.isNotBlank() } ?: appText(TextR.string.text_cannot_access_local_files_check_the_download_folder_and_permissions)
        else -> appText(TextR.string.text_failed_to_write_to_local_storage)
    }

    is AppError.Parse -> message.ifBlank { appText(TextR.string.text_unexpected_server_response_please_retry_later) }

    is AppError.Unsupported -> message

    is AppError.Unknown -> appText(TextR.string.text_an_unexpected_error_occurred_please_retry)
}
