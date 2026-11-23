package com.qgming.zlib.core.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** Guards missing translations and format arguments across the whole APK, including feature resources. */
class I18nResourcesTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private val modules = listOf("app", "core/common", "core/data", "core/designsystem", "feature/settings")

    @Test
    fun `every application string has English copy with the same formatting arguments`() {
        modules.forEach { module ->
            val defaults = strings(File(root, "$module/src/main/res/values"))
            val english = strings(File(root, "$module/src/main/res/values-en"))
            val translated = defaults.filterValues { !it.startsWith("@string/") }
            assertEquals(translated.keys, english.keys, module)
            translated.forEach { (key, value) ->
                val text = english.getValue(key)
                assertTrue(text.isNotBlank(), "$module:$key")
                assertEquals(arguments(value), arguments(text), "$module:$key")
                assertFalse(text.contains("\uFFFD"), "$module:$key")
                // Language names are written in their own language to remain recognizable.
                if (key != "text_simplified_chinese") assertFalse(text.any { it in '\u4E00'..'\u9FFF' }, "$module:$key")
            }
        }
    }

    @Test
    fun `locale config exposes Chinese and English and follows system without a duplicate preference`() {
        val file = File(root, "app/src/main/res/xml/locales_config.xml")
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("locale")
        assertEquals(listOf("zh-CN", "en"), (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") })
        assertEquals("", AppLanguage.SYSTEM.tag)
        val strings = strings(File(root, "core/common/src/main/res/values"))
        assertEquals("主题模式", strings.getValue("text_theme_mode"))
        assertEquals("软件语言", strings.getValue("text_app_language"))
        assertEquals("发现", strings.getValue("text_discover"))
        assertEquals("Z-library 拒绝搜索", strings.getValue("text_z_library_refused_the_search"))
        val settings = strings(File(root, "feature/settings/src/main/res/values-en"))
        assertFalse(settings.containsKey("about_license_notice_title"))
        assertFalse(settings.containsKey("about_license_body"))
    }

    private fun arguments(value: String) = Regex("%(?:[0-9]+\\$)?[-+0-9.]*[sdf]").findAll(value).map { it.value }.toList().sorted()

    private fun strings(directory: File): Map<String, String> = buildMap {
        directory.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).forEach { index ->
                val element = nodes.item(index) as Element
                val key = element.getAttribute("name")
                assertFalse(containsKey(key), "Duplicate $key in $directory")
                put(key, element.textContent.removeSurrounding("\""))
            }
        }
    }
}
