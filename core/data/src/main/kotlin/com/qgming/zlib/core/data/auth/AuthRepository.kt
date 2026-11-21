package com.qgming.zlib.core.data.auth

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.CookieImporter
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * All three login channels normalise to one [Session] (ADR-012). The password is never
 * persisted; cookies and remix credentials are written through [SessionManager].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: EapiClient,
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val registry: AllowedHostRegistry,
    private val dispatchers: AppDispatchers,
) {
    val session: StateFlow<Session?> = sessionManager.session

    suspend fun loginWithPassword(email: String, password: String): AppResult<Session> {
        if (email.isBlank() || password.isBlank()) {
            return AppResult.Failure(AppError.Parse("请输入邮箱和密码"))
        }
        ensureDomain()?.let { return AppResult.Failure(it) }

        return withContext(dispatchers.io) {
            when (val result = api.login(email.trim(), password)) {
                is AppResult.Success -> {
                    adopt(result.value)
                    result
                }

                is AppResult.Failure -> result
            }
        }
    }

    /**
     * Imports a cookie header / Netscape file / JSON object, validates it against the profile
     * endpoint and then persists it. [host] defaults to the current domain.
     */
    suspend fun importCookies(raw: String, host: String? = null): AppResult<Session> {
        val target = if (host != null) {
            DomainPolicy.normalizeHostInput(host)
                ?: return AppResult.Failure(AppError.Parse("WebView 登录域名无效"))
        } else {
            domainRepository.currentHostOrNull()
                ?: run {
                    ensureDomain()
                    domainRepository.currentHostOrNull()
                }
                ?: return AppResult.Failure(AppError.NoDomain)
        }

        if (!registry.isAllowed(target)) {
            return AppResult.Failure(AppError.Parse("域名不在允许列表"))
        }

        val imported = when (val parsed = CookieImporter.parse(raw, target)) {
            is AppResult.Failure -> return parsed
            is AppResult.Success -> parsed.value
        }

        val candidate = Session(
            remixUserId = imported.remixUserId,
            remixUserKey = imported.remixUserKey,
            domain = target,
            cookies = imported.cookies,
            createdAtMillis = System.currentTimeMillis(),
        )

        return withContext(dispatchers.io) {
            when (val profile = api.profile(candidate)) {
                is AppResult.Success -> {
                    adopt(candidate)
                    AppResult.Success(candidate)
                }

                is AppResult.Failure -> when (profile.error) {
                    AppError.SessionExpired, AppError.NotLoggedIn ->
                        AppResult.Failure(AppError.AuthFailed("Cookie 已失效或缺少登录态"))

                    else -> profile
                }
            }
        }
    }

    suspend fun logout() {
        withContext(dispatchers.io) { sessionManager.clear() }
    }

    private suspend fun adopt(session: Session) {
        sessionManager.save(session)
        domainRepository.adoptSessionHost(session.domain)
    }

    private suspend fun ensureDomain(): AppError? {
        if (domainRepository.currentHostOrNull() != null) return null
        domainRepository.initialize()
        if (domainRepository.currentHostOrNull() == null) {
            domainRepository.probeAll(switchToBest = true)
        }
        return if (domainRepository.currentHostOrNull() == null) AppError.NoDomain else null
    }
}
