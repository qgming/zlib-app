package com.qgming.zlib.core.data.account

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class AccountRepository @Inject constructor(
    private val api: EapiClient,
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val dispatchers: AppDispatchers,
) {
    val account = sessionManager.vault.map { vault -> vault.entries.firstOrNull { it.session.remixUserId == vault.activeId }?.profile }

    suspend fun refresh(id: String? = sessionManager.current()?.remixUserId): AppResult<Account> {
        var session = sessionManager.vault.value.entries.firstOrNull { it.session.remixUserId == id }?.session
            ?: return AppResult.Failure(AppError.NotLoggedIn)
        return withContext(dispatchers.io) {
            var result = api.profile(session)
            // Some mirrors expose login/search but not /eapi/user/profile. Try one healthy
            // alternative before surfacing "接口不存在" (keeps cookies, ADR-013).
            val firstError = (result as? AppResult.Failure)?.error
            if (firstError is AppError.Server && firstError.code == 404 && sessionManager.current() == session) {
                domainRepository.probeAll(switchToBest = true)
                sessionManager.current()?.takeIf { it.remixUserId == id }?.let { session = it; result = api.profile(it) }
            }
            when (result) {
                is AppResult.Success -> sessionManager.updateProfile(session, result.value)
                is AppResult.Failure -> if (result.error == AppError.SessionExpired) {
                    sessionManager.expire(session)
                }
            }
            result
        }
    }

}
