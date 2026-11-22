package com.qgming.zlib.core.data.account

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called only after a file request explicitly returned a quota error. Never logs credentials. */
@Singleton
class AccountQuotaSwitcher internal constructor(
    private val sessions: SessionManager,
    private val profile: suspend (Session) -> AppResult<Account>,
) {
    @Inject constructor(sessions: SessionManager, api: EapiClient) : this(sessions, api::profile)
    private val lock = Mutex()

    suspend fun switchAfterQuota(failed: Session, attempted: MutableSet<String>): Boolean = lock.withLock {
        attempted += failed.remixUserId
        if (!sessions.vault.value.autoSwitch || sessions.current() != failed) return@withLock false
        val current = (profile(failed) as? AppResult.Success)?.value ?: return@withLock false
        if (!sessions.updateProfile(failed, current)) return@withLock false
        if (!current.quota.known || current.quota.amount == null || current.quota.remaining != 0) return@withLock false
        for (entry in sessions.vault.value.entries) {
            val candidate = entry.session
            if (entry.expired || !attempted.add(candidate.remixUserId)) continue
            if (!sessions.vault.value.autoSwitch || sessions.current() != failed) return@withLock false
            when (val result = profile(candidate.withDomain(failed.domain))) {
                is AppResult.Success -> {
                    if (!sessions.updateProfile(candidate, result.value)) continue
                    if (result.value.quota.known && result.value.quota.amount != null && (result.value.quota.remaining ?: 0) > 0 && sessions.vault.value.autoSwitch) {
                        return@withLock sessions.activate(candidate.remixUserId, host = failed.domain, expected = failed)
                    }
                }
                is AppResult.Failure -> if (result.error == AppError.SessionExpired || result.error == AppError.NotLoggedIn) {
                    sessions.expire(candidate)
                }
            }
        }
        false
    }
}
