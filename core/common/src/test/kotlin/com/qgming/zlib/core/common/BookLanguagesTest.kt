package com.qgming.zlib.core.common

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookLanguagesTest {
    @Test
    fun `English names and ISO codes display in the requested app language`() {
        for (raw in listOf("english", " English ", "en", "eng", "英语")) {
            assertEquals("英语", BookLanguages.displayName(raw, Locale.SIMPLIFIED_CHINESE), raw)
            assertEquals("English", BookLanguages.displayName(raw, Locale.ENGLISH), raw)
        }
        assertEquals("德语", BookLanguages.displayName("ger", Locale.CHINA))
        assertEquals("法语", BookLanguages.displayName("fra", Locale.CHINA))
        assertEquals("Japanese", BookLanguages.displayName("日语", Locale.ENGLISH))
        assertEquals("俄语", BookLanguages.displayName("russian", Locale.CHINA))
    }

    @Test
    fun `scripts and regional codes keep the language distinction`() {
        assertEquals(BookLanguages.displayName("zh-Hans", Locale.ENGLISH), BookLanguages.displayName("Chinese (Simplified)", Locale.ENGLISH))
        assertEquals(BookLanguages.displayName("zh-Hant", Locale.CHINA), BookLanguages.displayName("繁體中文", Locale.CHINA))
        assertTrue(BookLanguages.displayName("traditional chinese", Locale.CHINA).contains("繁体"))
        assertTrue(BookLanguages.displayName("pt_BR", Locale.ENGLISH).contains("Brazil"))
    }

    @Test
    fun `multiple languages translate individually while unknown values stay intact`() {
        assertEquals("英语 / 中文", BookLanguages.displayName("english, chinese", Locale.CHINA))
        assertEquals("English / Chinese / Atlantis", BookLanguages.displayName("英语 / 中文; Atlantis", Locale.ENGLISH))
        assertEquals("English", BookLanguages.displayName("en; english; eng", Locale.ENGLISH))
        assertEquals("Unknown Dialect", BookLanguages.displayName(" Unknown Dialect ", Locale.CHINA))
        assertEquals("xyz", BookLanguages.displayName("xyz", Locale.CHINA))
        assertEquals("", BookLanguages.displayName("  ", Locale.CHINA))
    }

    @Test
    fun `catalogue historical names resolve before splitting embedded commas`() {
        assertEquals(BookLanguages.displayName("ang", Locale.CHINA), BookLanguages.displayName("english, old (ca.450-1100)", Locale.CHINA))
        assertEquals("Old English", BookLanguages.displayName("old english", Locale.ENGLISH))
        assertEquals("French", BookLanguages.displayName("french", Locale.FRANCE))
    }

    @Test
    fun `verified catalogue aliases keep their specific languages instead of guessing from prefixes`() {
        assertEquals("德语 / 俄语", BookLanguages.displayName("german-russian", Locale.CHINA))
        assertEquals(BookLanguages.displayName("nb", Locale.CHINA), BookLanguages.displayName("norwegian_bokmal", Locale.CHINA))
        assertEquals(BookLanguages.displayName("nn", Locale.ENGLISH), BookLanguages.displayName("norwegian_nynorsk", Locale.ENGLISH))
        assertEquals(BookLanguages.displayName("pt-BR", Locale.ENGLISH), BookLanguages.displayName("brazilian", Locale.ENGLISH))
        assertEquals(BookLanguages.displayName("km", Locale.CHINA), BookLanguages.displayName("central_khmer", Locale.CHINA))
        assertEquals("原住民语言", BookLanguages.displayName("indigenous", Locale.CHINA))
        assertEquals("german-made-up", BookLanguages.displayName("german-made-up", Locale.CHINA))
    }

    @Test
    fun `every language category from the anonymous catalogue has Chinese and English display names`() {
        val categories = javaClass.getResourceAsStream("/book-language-codes.txt")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(175, categories.size)
        for (category in categories) {
            val chinese = BookLanguages.displayName(category, Locale.CHINA)
            assertTrue(chinese.any { it in '\u4E00'..'\u9FFF' }, "$category -> $chinese")
            val english = BookLanguages.displayName(category, Locale.ENGLISH)
            assertTrue(english.isNotBlank() && english.none { it in '\u4E00'..'\u9FFF' }, "$category -> $english")
        }
    }
}
