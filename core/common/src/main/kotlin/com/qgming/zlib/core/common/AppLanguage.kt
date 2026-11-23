package com.qgming.zlib.core.common

import java.util.Locale

/** Android owns persistence; an empty language list means follow the device. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

    companion object {
        fun fromLanguageTags(tags: String): AppLanguage {
            val first = tags.substringBefore(',').trim()
            if (first.isEmpty()) return SYSTEM
            return when (Locale.forLanguageTag(first).language) {
                "zh" -> SIMPLIFIED_CHINESE
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}
