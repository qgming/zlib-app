package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Cloud favorites are never stored as independent local flags. */
@Singleton
class FavoritesRepository @Inject constructor(
    private val api: EapiClient,
    private val sessions: SessionManager,
) {
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    suspend fun isSaved(id: String): AppResult<Boolean> {
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        val result = api.isBookSaved(id)
        return if (sessions.current() == session) result else AppResult.Failure(AppError.SessionExpired)
    }

    suspend fun setSaved(id: String, saved: Boolean): AppResult<Unit> {
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        val result = api.setBookSaved(id, saved)
        if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
        if (result is AppResult.Success) _changes.update { it + 1 }
        return result
    }
}
