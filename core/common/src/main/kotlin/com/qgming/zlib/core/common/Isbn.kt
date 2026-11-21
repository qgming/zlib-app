package com.qgming.zlib.core.common

/** ISBN canonicalisation and validation. See docs/EAPI.md §5. */
object Isbn {

    private val separators = Regex("[\\s\\-–—]")

    /** Removes separators and uppercases, then returns the value only if it is a valid ISBN. */
    fun normalize(raw: String?): String? {
        val value = strip(raw)
        return if (isValid10(value) || isValid13(value)) value else null
    }

    fun isValid(value: String?): Boolean {
        val stripped = strip(value)
        return isValid10(stripped) || isValid13(stripped)
    }

    private fun strip(raw: String?): String =
        raw?.trim()?.replace(separators, "")?.uppercase().orEmpty()

    fun isValid10(value: String?): Boolean {
        val v = value?.uppercase() ?: return false
        if (v.length != 10) return false
        if (!v.take(9).all { it.isDigit() }) return false
        if (!(v[9].isDigit() || v[9] == 'X')) return false
        var sum = 0
        for (i in 0 until 10) {
            val digit = if (i == 9 && v[i] == 'X') 10 else v[i] - '0'
            sum += (10 - i) * digit
        }
        return sum % 11 == 0
    }

    fun isValid13(value: String?): Boolean {
        val v = value ?: return false
        if (v.length != 13 || !v.all { it.isDigit() }) return false
        var sum = 0
        for (i in 0 until 13) {
            sum += (v[i] - '0') * if (i % 2 == 0) 1 else 3
        }
        return sum % 10 == 0
    }

    /** Converts a valid ISBN-10 to its ISBN-13 (978 prefix), or null. */
    fun toIsbn13(value: String?): String? {
        val v = value?.uppercase() ?: return null
        if (!isValid10(v)) return null
        val body = "978" + v.take(9)
        var sum = 0
        for (i in body.indices) {
            sum += (body[i] - '0') * if (i % 2 == 0) 1 else 3
        }
        val check = (10 - sum % 10) % 10
        return body + check
    }

    /** Converts a 978-prefixed valid ISBN-13 to ISBN-10, or null for 979 prefixes. */
    fun toIsbn10(value: String?): String? {
        val v = value ?: return null
        if (!isValid13(v) || !v.startsWith("978")) return null
        val body = v.substring(3, 12)
        var sum = 0
        for (i in 0 until 9) {
            sum += (body[i] - '0') * (10 - i)
        }
        val check = (11 - sum % 11) % 11
        return body + if (check == 10) "X" else check.toString()
    }

    /** Extracts every distinct valid ISBN from a loosely formatted identifier string. */
    fun extract(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ';', '|', '\n', '\r', '/')
            .asSequence()
            .mapNotNull { normalize(it) }
            .distinct()
            .toList()
    }
}
