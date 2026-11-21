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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class AccountRepository @Inject constructor(
    private val api: EapiClient,
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val dispatchers: AppDispatchers,
) {
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    suspend fun refresh(): AppResult<Account> {
        if (sessionManager.current() == null) return AppResult.Failure(AppError.NotLoggedIn)
        return withContext(dispatchers.io) {
            var result = api.profile()
            // Some mirrors expose login/search but not /eapi/user/profile. Try one healthy
            // alternative before surfacing "接口不存在" (keeps cookies, ADR-013).
            val firstError = (result as? AppResult.Failure)?.error
            if (firstError is AppError.Server && firstError.code == 404) {
                domainRepository.probeAll(switchToBest = true)
                result = api.profile()
            }
            when (result) {
                is AppResult.Success -> _account.value = result.value
                is AppResult.Failure -> if (result.error == AppError.SessionExpired) {
                    sessionManager.clear()
                    _account.value = null
                }
            }
            result
        }
    }

    fun clear() {
        _account.value = null
    }
}
