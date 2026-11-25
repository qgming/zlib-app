package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import java.net.ProtocolException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Application-level retry for documented PAPI reads only; the transport never replays calls. */
internal suspend fun <T> retryPapiRead(isCurrentSession: () -> Boolean, read: suspend () -> AppResult<T>): AppResult<T> {
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentSession()) return AppResult.Failure(AppError.NotLoggedIn)
        val result = read()
        currentCoroutineContext().ensureActive()
        if (!isCurrentSession()) return AppResult.Failure(AppError.NotLoggedIn)
        if (result !is AppResult.Failure || !result.error.isTransientReadFailure() || retries == MAX_RETRIES) return result
        delay(1_000L shl retries++)
    }
}

private fun AppError.isTransientReadFailure(): Boolean = when (this) {
    is AppError.Server -> code in 502..504
    is AppError.Network -> generateSequence(cause, Throwable::cause).none {
        it is SSLException || it is ProtocolException || it is CertificateException
    }
    else -> false
}

private const val MAX_RETRIES = 3
