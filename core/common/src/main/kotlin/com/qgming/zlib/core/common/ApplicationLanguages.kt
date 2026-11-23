package com.qgming.zlib.core.common

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/** No duplicated preferences: matches the system's per-app language picker on API 33+. */
class ApplicationLanguages(context: Context) {
    private val manager = context.getSystemService(LocaleManager::class.java)

    val selected: AppLanguage get() = AppLanguage.fromLanguageTags(manager.applicationLocales.toLanguageTags())

    fun select(language: AppLanguage) {
        val locales = LocaleList.forLanguageTags(language.tag)
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    }
}
