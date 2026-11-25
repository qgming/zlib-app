package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PapiReadRetryTest {
    @Test
    fun `retry waits one two and four seconds then returns the fourth result`() = runTest {
        val times = mutableListOf<Long>()
        val failure = AppResult.Failure(AppError.Server(503, ""))
        val result = retryPapiRead({ true }) {
            times += testScheduler.currentTime
            failure
        }
        assertEquals(failure, result)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), times)
    }

    @Test
    fun `temporary network and gateway failures recover without extra requests`() = runTest {
        val responses = ArrayDeque(listOf(
            AppResult.Failure(AppError.Network(SocketTimeoutException())),
            AppResult.Failure(AppError.Server(502, "")),
            AppResult.Success("Read succeeded"),
        ))
        assertEquals(AppResult.Success("Read succeeded"), retryPapiRead({ true }) { responses.removeFirst() })
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(0, responses.size)
    }

    @Test
    fun `authentication limits challenges and permanent errors do not retry`() = runTest {
        val errors = listOf(
            AppError.NotLoggedIn,
            AppError.SessionExpired,
            AppError.Server(401, ""),
            AppError.Server(429, ""),
            AppError.Server(500, ""),
            AppError.DomainBlocked(DomainStatus.DIAMWALL),
            AppError.DomainBlocked(DomainStatus.CHALLENGED),
            AppError.Parse("bad payload"),
            AppError.Unsupported("Request refused"),
            AppError.Network(SSLHandshakeException("not trusted")),
            AppError.Network(ProtocolException("wrong protocol")),
        )
        for (error in errors) {
            var count = 0
            val failure = AppResult.Failure(error)
            val result = retryPapiRead({ true }) { count++; failure }
            assertEquals(failure, result)
            assertEquals(1, count, error.toString())
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `session changes and cancellation stop backoff before replay`() = runTest {
        var current = true
        var count = 0
        val failure = AppResult.Failure(AppError.Server(504, ""))
        val request = launch {
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), retryPapiRead({ current }) { count++; failure })
        }
        runCurrent()
        assertEquals(1, count)
        current = false
        advanceTimeBy(1_000)
        request.join()
        assertEquals(1, count)

        val cancelled = launch {
            retryPapiRead({ true }) { count++; failure }
            error("Cancelled reads must not deliver a result")
        }
        runCurrent()
        assertEquals(2, count)
        cancelled.cancelAndJoin()
        advanceTimeBy(8_000)
        assertEquals(2, count)
        assertFalse(cancelled.isActive)
    }
}
