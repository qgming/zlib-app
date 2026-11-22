package com.qgming.zlib.navigation

import java.util.Locale

internal enum class SharedViewCoordinates { SCREEN, WINDOW }

/** Parses the device's public capability metadata, never the device brand or API level. */
internal object SharedViewCapabilities {
    fun coordinates(metadata: String?): SharedViewCoordinates {
        val text = metadata.orEmpty().uppercase(Locale.ROOT)
        return when (text.split('|').getOrNull(1)?.trim()) {
            "SCREEN" -> SharedViewCoordinates.SCREEN
            "WINDOW" -> SharedViewCoordinates.WINDOW
            else -> if ("WINDOW" in text) SharedViewCoordinates.WINDOW else SharedViewCoordinates.SCREEN
        }
    }

    fun supportsAnimation(metadata: String?, name: String): Boolean {
        if (metadata == null) return false
        val fields = metadata.split('|')
        if (fields.firstOrNull()?.trim() != "int") return false
        // A bare int declares a writable parameter but no restricted value set.
        val values = fields.getOrNull(1)?.trim().orEmpty()
        return values.isEmpty() || !values.startsWith("VIEW_ANIM_") || values.split(',').any { it.trim() == name }
    }
}
