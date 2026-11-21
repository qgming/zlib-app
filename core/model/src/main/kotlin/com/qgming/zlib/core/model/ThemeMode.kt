package com.qgming.zlib.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
