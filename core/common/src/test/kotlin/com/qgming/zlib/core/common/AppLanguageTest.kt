package com.qgming.zlib.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun `empty preference follows system while explicit language tags retain their selection`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(" "))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-Hans-CN,en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US,zh-CN"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("fr-FR"))
    }
}
