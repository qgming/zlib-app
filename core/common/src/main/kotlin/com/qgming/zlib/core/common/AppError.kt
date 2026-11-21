package com.qgming.zlib.core.common

/**
 * Coarse-grained domain health state. Shared by the network layer (probe results),
 * the data layer (domain switching) and the UI (actionable messages).
 */
enum class DomainStatus {
    UNKNOWN,
    PROBING,
    HEALTHY,
    CHALLENGED,
    DIAMWALL,
    REDIRECT_LOOP,
    HTTP_ERROR,
    NETWORK_ERROR,
    INVALID,
}

/** Unified, UI-mappable error model. See docs/ARCHITECTURE.md §7 and docs/EAPI.md §4. */
sealed interface AppError {
    data object NoDomain : AppError
    data object NotLoggedIn : AppError
    data object SessionExpired : AppError
    data class AuthFailed(val message: String? = null) : AppError
    data class Quota(val remaining: Int?, val resetHint: String? = null) : AppError
    data class DomainBlocked(val status: DomainStatus) : AppError
    data class Network(val cause: Throwable) : AppError
    data class Server(val code: Int, val message: String) : AppError
    data class Storage(val cause: Throwable) : AppError
    data class Parse(val message: String) : AppError
    data class Unsupported(val message: String) : AppError
    data class Unknown(val cause: Throwable) : AppError
}

/** A [kotlin.Result]-like type that carries a typed [AppError] instead of an exception. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

fun <T> AppResult<T>.getOrNull(): T? = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> null
}

fun <T> AppResult<T>.getOrElse(fallback: (AppError) -> T): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> fallback(error)
}

fun <T> AppResult<T>.errorOrNull(): AppError? = when (this) {
    is AppResult.Success -> null
    is AppResult.Failure -> error
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/** Like [map], but turns a throwing transform into [AppError.Parse] instead of crashing. */
inline fun <T, R> AppResult<T>.mapCatching(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> try {
        AppResult.Success(transform(value))
    } catch (t: Throwable) {
        AppResult.Failure(t.toAppError())
    }

    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    AppResult.Failure(t.toAppError())
}

fun Throwable.toAppError(): AppError = when (this) {
    is AppErrorException -> error
    is java.io.IOException -> AppError.Network(this)
    else -> AppError.Unknown(this)
}

class AppErrorException(val error: AppError) : Exception(error.toString())
