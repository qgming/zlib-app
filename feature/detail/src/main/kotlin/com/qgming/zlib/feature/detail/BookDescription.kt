package com.qgming.zlib.feature.detail

/** Formats only the description already returned in EAPI JSON; never loads external content. */
internal object BookDescription {
    private val hiddenContent = Regex(
        """<!--.*?-->|<(script|style|iframe|object)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tag = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9]*)\b[^>]*>""")
    private val escapedNewline = Regex("""\\r\\n|\\[nr]""")
    private val lineBreak = Regex("""[ \t]*\n[ \t]*""")
    private val repeatedBreak = Regex("""(?:<br>\s*){3,}""")
    private val edgeBreak = Regex("""^(?:\s*<br>)+|(?:<br>\s*)+$""")
    private val blocks = setOf("p", "div", "section", "article", "header", "footer", "blockquote", "tr", "pre")
    private val emphasis = mapOf("strong" to "b", "em" to "i", "strike" to "s", "del" to "s")
    private val inline = setOf("b", "i", "u", "s", "sub", "sup")

    fun html(raw: String): String {
        val source = raw
            .replace(hiddenContent, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u2029", "\n\n")
            .replace('\u2028', '\n')
            .replace(escapedNewline, "\n")
        // Null denotes an unordered list; a number is the next ordered-list item.
        val lists = mutableListOf<Int?>()
        val result = buildString {
            var cursor = 0
            for (match in tag.findAll(source)) {
                append(text(source.substring(cursor, match.range.first)))
                val closing = match.groupValues[1].isNotEmpty()
                val name = match.groupValues[2].lowercase()
                val style = emphasis[name] ?: name
                when {
                    name == "br" -> append("<br>")
                    name == "hr" || name in blocks -> append("<br><br>")
                    name in setOf("h1", "h2", "h3", "h4", "h5", "h6") ->
                        append(if (closing) "</b><br><br>" else "<br><br><b>")
                    name == "ul" || name == "ol" -> {
                        append("<br><br>")
                        if (closing) {
                            if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                        } else lists.add(if (name == "ol") 1 else null)
                    }
                    name == "li" -> {
                        if (!closing) {
                            append("<br>")
                            val number = lists.lastOrNull()
                            if (number == null) append("• ") else {
                                append("$number. ")
                                lists[lists.lastIndex] = number + 1
                            }
                        }
                    }
                    style in inline -> append(if (closing) "</$style>" else "<$style>")
                    name == "td" && closing -> append(" ")
                    // Links keep their visible text; images, CSS and unknown tags add no behavior.
                }
                cursor = match.range.last + 1
            }
            append(text(source.substring(cursor)))
        }
        return result.trim()
            .replace(repeatedBreak, "<br><br>")
            .replace(edgeBreak, "")
            .trim()
    }

    private fun text(value: String): String = value
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(lineBreak, "<br>")
}
