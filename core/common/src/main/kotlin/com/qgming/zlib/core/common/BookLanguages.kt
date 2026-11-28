package com.qgming.zlib.core.common

import java.util.Locale

/** Display-only language names. Never changes the server value or search filter codes. */
object BookLanguages {
    private val chinese = Locale.SIMPLIFIED_CHINESE
    private val aliases: Map<String, Locale> by lazy {
        buildMap {
            val codes = Locale.getISOLanguages().toSet() + setOf("ang", "enm", "grc", "ber", "crh", "kaa", "myv", "mdf")
            codes.forEach { code ->
                val language = Locale.forLanguageTag(code)
                listOf(code, language.getISO3Language(), language.getDisplayLanguage(Locale.ENGLISH),
                    language.getDisplayLanguage(chinese), language.getDisplayLanguage(Locale.TRADITIONAL_CHINESE))
                    .forEach { put(key(it), language) }
            }
            // ISO 639-2 bibliographic aliases are also used in imported catalogues.
            mapOf("chi" to "zh", "ger" to "de", "fre" to "fr", "dut" to "nl", "cze" to "cs",
                "gre" to "el", "ice" to "is", "rum" to "ro", "slo" to "sk", "alb" to "sq",
                "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "mac" to "mk",
                "may" to "ms", "per" to "fa", "tib" to "bo", "wel" to "cy")
                .forEach { (alias, code) -> put(alias, Locale.forLanguageTag(code)) }
            mapOf(
                "zh-Hans" to listOf("simplified chinese", "chinese (simplified)", "chinese simplified", "简体中文", "简体汉语", "中文（简体）", "中文(简体)", "簡體中文"),
                "zh-Hant" to listOf("traditional chinese", "chinese (traditional)", "chinese traditional", "繁体中文", "繁體中文", "中文（繁体）", "中文(繁体)", "中文（繁體）"),
                "fa" to listOf("farsi"),
                "bn" to listOf("bengali"),
                "es" to listOf("castilian"),
                "ro" to listOf("moldavian", "moldovan"),
                "nb" to listOf("norwegian_bokmal", "norwegian bokmål"),
                "os" to listOf("ossetian"),
                "km" to listOf("central_khmer"),
                "ber" to listOf("berber"),
                "pt-BR" to listOf("brazilian", "brazilian portuguese"),
                "ny" to listOf("chichewa"),
                "crh" to listOf("crimean", "crimean tatar"),
                "ff" to listOf("fulah"),
                "gd" to listOf("gaelic"),
                "ht" to listOf("haitian"),
                "kaa" to listOf("karakalpak"),
                "li" to listOf("limburgan"),
                "ms" to listOf("malaysian"),
                "mi" to listOf("maori"),
                "ie" to listOf("occidental"),
                "myv" to listOf("erzya"),
                "tl" to listOf("tagalog (filipino)"),
                "ang" to listOf("ang", "old english", "english, old (ca.450-1100)", "古英语"),
                "enm" to listOf("enm", "middle english", "english, middle (1100-1500)", "中古英语"),
                "grc" to listOf("grc", "ancient greek", "greek, ancient (to 1453)", "古希腊语"),
                "la" to listOf("latin", "拉丁语", "拉丁文"),
            ).forEach { (tag, names) -> names.forEach { put(key(it), Locale.forLanguageTag(tag)) } }
        }
    }

    fun displayName(raw: String, displayLocale: Locale): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val locale = if (displayLocale.language == "zh") chinese else Locale.ENGLISH
        displaySingle(value, locale)?.let { return it }
        return value.split(Regex("\\s*[,;/|、+]\\s*"))
            .filter(String::isNotBlank)
            .map { part -> displaySingle(part, locale) ?: part }
            .distinct()
            .joinToString(" / ")
    }

    private fun displaySingle(value: String, locale: Locale): String? = when (key(value)) {
        "german-russian" -> listOf("de", "ru").joinToString(" / ") { Locale.forLanguageTag(it).getDisplayLanguage(locale) }
        "indigenous" -> if (locale.language == "zh") "原住民语言" else "Indigenous languages"
        else -> resolve(value)?.getDisplayName(locale)
    }

    private fun resolve(value: String): Locale? {
        aliases[key(value)]?.let { return it }
        val tag = value.trim().replace('_', '-')
        if (!tag.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?"))) return null
        val candidate = Locale.forLanguageTag(tag)
        return candidate.takeIf { aliases[it.language] != null }
    }

    private fun key(value: String): String = value.trim().lowercase(Locale.ROOT).replace('_', ' ').replace(Regex("\\s+"), " ")
}
