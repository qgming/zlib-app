package com.qgming.zlib.feature.detail

internal data class ContributorName(val start: Int, val end: Int, val query: String)

/** Finds link ranges without rewriting the server's author/translator credit line. */
internal object ContributorNames {
    private val brackets = Regex("""\[[^\]\r\n]*]|［[^］\r\n]*］|【[^】\r\n]*】|\([^()\r\n]*\)|（[^（）\r\n]*）""")
    private val separators = Regex("""[,，;；、/／|｜&＆+＋\r\n\u2028\u2029]+|\s+(?:and|with|与|和|及)\s+""", RegexOption.IGNORE_CASE)
    private val roles = Regex(
        """作者|著者|译者|譯者|翻译|翻譯|编者|編者|编著|編著|编译|編譯|主编|主編|编写|編寫|合著|合译|合譯|校订|校訂|校译|校譯|审校|審校|校注|注释|注釋|整理|选编|選編|绘者|繪者|插图|插圖|绘图|繪圖|摄影|攝影|\b(?:(?:written|translated|edited|illustrated)\s+by|authors?|translators?|editors?|illustrators?|et\s+al\.?)\b|[著译譯编編绘繪撰注校等](?=\s|[:：]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val nameText = Regex("""[\p{L}\p{M}\p{N} .．·•・\-'’]+""")
    private val noName = setOf("佚名", "无名氏", "不详", "未知", "作者不详", "著者不详", "译者不详", "anonymous", "unknown", "n/a")
    private val countries = (
        "中 中国 美 美国 英 英国 法 法国 德 德国 日 日本 意 意大利 俄 俄罗斯 苏 苏联 加 加拿大 澳 澳大利亚 奥 奥地利 " +
            "瑞士 瑞典 西 西班牙 韩 韩国 荷 荷兰 葡 葡萄牙 挪 挪威 丹 丹麦 芬 芬兰 比 比利时 巴西 阿根廷 以色列 南非 " +
            "印 印度 爱尔兰 波兰 匈牙利 捷克 希腊 冰岛 新西兰 墨西哥 台湾 香港 中国台湾 中国香港 " +
            "清 明 宋 唐 元 汉 晋 三国 战国 春秋 us usa uk u.s. u.s.a. american british french german japanese russian canadian australian"
        ).split(' ').toSet()

    fun parse(text: String): List<ContributorName> {
        val result = mutableListOf<ContributorName>()
        var cursor = 0
        for (match in brackets.findAll(text)) {
            parseNames(text, cursor, match.range.first, result)
            val innerStart = match.range.first + 1
            val innerEnd = match.range.last
            val inner = text.substring(innerStart, innerEnd).trim()
            // Square brackets conventionally describe nationality/roles. Parentheses may hold an alias.
            if (match.value.first() in "(（" && !isLabel(inner)) {
                parseNames(text, innerStart, innerEnd, result)
            }
            cursor = match.range.last + 1
        }
        parseNames(text, cursor, text.length, result)
        return result
    }

    private fun isLabel(text: String): Boolean = text.isEmpty() ||
        text.lowercase() in countries || roles.matches(text) ||
        text.none(Char::isLetter) ||
        text.split('/', '／', '、').all { it.trim().lowercase() in countries }

    private fun parseNames(text: String, start: Int, end: Int, result: MutableList<ContributorName>) {
        val region = text.substring(start, end)
        var cursor = 0
        for (separator in separators.findAll(region)) {
            parseCredit(text, start + cursor, start + separator.range.first, result)
            cursor = separator.range.last + 1
        }
        parseCredit(text, start + cursor, end, result)
    }

    private fun parseCredit(text: String, start: Int, end: Int, result: MutableList<ContributorName>) {
        val region = text.substring(start, end)
        var cursor = 0
        for (role in roles.findAll(region)) {
            // A two-character name such as 谢译 must not lose its final character.
            val preceding = region.substring(cursor, role.range.first).trim()
            if (role.value.length == 1 && preceding.isNotEmpty() && preceding.count(Char::isLetter) < 2) continue
            addName(text, start + cursor, start + role.range.first, result)
            cursor = role.range.last + 1
        }
        addName(text, start + cursor, end, result)
    }

    private fun addName(text: String, start: Int, end: Int, result: MutableList<ContributorName>) {
        var left = start
        var right = end
        while (left < right && (text[left].isWhitespace() || text[left] in ":：")) left++
        while (right > left && (text[right - 1].isWhitespace() || text[right - 1] in ":：")) right--
        val name = text.substring(left, right)
        if (name.isNotEmpty() && nameText.matches(name) && name.any(Char::isLetter) && name.lowercase() !in noName) {
            result.add(ContributorName(left, right, name))
        }
    }
}
