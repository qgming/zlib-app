package com.qgming.zlib.core.data.auth

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.download.DownloadRunner
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.CookieImporter
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/**
 * Password login and Cookie import normalise to one [Session] (ADR-052). The password is never
 * persisted; cookies and remix credentials are written through [SessionManager].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: EapiClient,
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val registry: AllowedHostRegistry,
    private val dispatchers: AppDispatchers,
    private val downloads: DownloadRunner,
) {
    val session: StateFlow<Session?> = sessionManager.session
    val accounts = sessionManager.vault

    suspend fun loginWithPassword(email: String, password: String): AppResult<Session> {
        if (email.isBlank() || password.isBlank()) {
            return AppResult.Failure(AppError.Parse("请输入邮箱和密码"))
        }
        ensureDomain()?.let { return AppResult.Failure(it) }

        return withContext(dispatchers.io) {
            when (val result = api.login(email.trim(), password)) {
                is AppResult.Success -> {
                    when (val profile = api.profile(result.value)) {
                        is AppResult.Success -> { adopt(result.value, profile.value); result }
                        is AppResult.Failure -> profile
                    }
                }

                is AppResult.Failure -> result
            }
        }
    }

    /**
     * Imports a cookie header / Netscape file / JSON object, validates it against the profile
     * endpoint on the current domain and then persists it.
     */
    suspend fun importCookies(raw: String): AppResult<Session> {
        val target = domainRepository.currentHostOrNull()
                ?: run {
                    ensureDomain()
                    domainRepository.currentHostOrNull()
                }
                ?: return AppResult.Failure(AppError.NoDomain)

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
                    adopt(candidate, profile.value)
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

    suspend fun switchAccount(id: String): AppResult<Unit> {
        val expected = sessionManager.current()
        val entry = sessionManager.vault.value.entries.firstOrNull { it.session.remixUserId == id }
            ?: return AppResult.Failure(AppError.Parse("账号已不存在"))
        val candidate = entry.session.withDomain(domainRepository.currentHostOrNull() ?: entry.session.domain)
        when (val profile = api.profile(candidate)) {
            is AppResult.Failure -> {
                if (profile.error == AppError.SessionExpired) sessionManager.expire(entry.session)
                return profile
            }
            is AppResult.Success -> {
                sessionManager.updateProfile(entry.session, profile.value)
                return downloads.withAccountChange {
                    if (sessionManager.current() != expected) return@withAccountChange AppResult.Failure(AppError.Parse("当前账号已改变，请重试"))
                    withContext(NonCancellable) {
                        if (sessionManager.activate(id, candidate.domain, expected)) AppResult.Success(Unit)
                        else AppResult.Failure(AppError.Parse("无法切换账号，请重新登录"))
                    }
                }
            }
        }
    }

    suspend fun logout(id: String? = sessionManager.current()?.remixUserId) {
        if (id == null) return
        downloads.withAccountChange(stopIf = { sessionManager.current()?.remixUserId == id }) {
            withContext(NonCancellable) { sessionManager.remove(id) }
        }
    }

    private suspend fun adopt(session: Session, profile: Account) {
        downloads.withAccountChange {
            withContext(NonCancellable) {
                sessionManager.add(session.copy(createdAtMillis = System.currentTimeMillis()), profile)
                domainRepository.adoptSessionHost(session.domain)
            }
        }
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
