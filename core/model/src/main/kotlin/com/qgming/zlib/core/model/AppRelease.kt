package com.qgming.zlib.core.model

import com.qgming.zlib.core.common.AppError

data class ReleaseApk(val name: String, val url: String, val size: Long, val sha256: String? = null)

data class AppRelease(
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val title: String = tag,
    val publishedAt: String? = null,
    val apks: List<ReleaseApk> = emptyList(),
)

fun AppRelease.apkFor(supportedAbis: List<String>): ReleaseApk? {
    val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    fun ReleaseApk.abi(): String? = knownAbis.firstOrNull { name.contains(it, ignoreCase = true) }
    supportedAbis.forEach { abi -> apks.singleOrNull { it.abi() == abi }?.let { return it } }
    return apks.singleOrNull { it.abi() == null }
}

sealed interface UpdateCheckResult {
    data object NoRelease : UpdateCheckResult
    data class Current(val release: AppRelease) : UpdateCheckResult
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class Uncomparable(val release: AppRelease) : UpdateCheckResult
    data class Cached(val release: AppRelease, val failure: AppError) : UpdateCheckResult
}

/** SemVer precedence; an optional v prefix and build metadata do not affect comparison. */
fun compareReleaseVersions(release: String, installed: String): Int? {
    val latest = ReleaseVersion.parse(release) ?: return null
    val current = ReleaseVersion.parse(installed) ?: return null
    return latest.compareTo(current)
}

private data class ReleaseVersion(val numbers: List<String>, val prerelease: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            compareNumber(left, right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) return if (prerelease.isEmpty()) 1 else -1
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val result = when {
                leftNumeric && rightNumeric -> compareNumber(left, right)
                leftNumeric != rightNumeric -> if (leftNumeric) -1 else 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (prerelease.any { it.length > 1 && it[0] == '0' && it.all(Char::isDigit) }) return null
            return ReleaseVersion(match.groupValues.subList(1, 4), prerelease)
        }

        private fun compareNumber(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
