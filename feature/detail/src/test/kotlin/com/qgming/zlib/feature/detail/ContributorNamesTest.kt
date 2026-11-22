package com.qgming.zlib.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContributorNamesTest {
    @Test
    fun `actual multi author and translator credit keeps independent names`() {
        assertNames("岸见一郎, 古贺史健; 渠海霞", "岸见一郎", "古贺史健", "渠海霞")
    }

    @Test
    fun `nationalities roles punctuation and spaces are outside each link`() {
        val raw = "作者：[美] 丹尼尔·卡尼曼 著；译者：胡晓姣、李爱民、何梦莹 译"
        val ranges = assertNames(raw, "丹尼尔·卡尼曼", "胡晓姣", "李爱民", "何梦莹")
        assertEquals("作者：[美]  著；译者：、、 译", withoutNames(raw, ranges))
    }

    @Test
    fun `full width and round nationality markers remain ordinary text`() {
        val raw = "（英）J. K. Rowling / ［法］安托万·德·圣埃克苏佩里；【中】李继宏（译）"
        val ranges = assertNames(raw, "J. K. Rowling", "安托万·德·圣埃克苏佩里", "李继宏")
        assertEquals("（英） / ［法］；【中】（译）", withoutNames(raw, ranges))
    }

    @Test
    fun `latin names keep spaces initials hyphens accents and apostrophes`() {
        assertNames(
            "[US] Mary-Jane O'Connor and José Saramago; J. R. R. Tolkien & Ursula K. Le Guin",
            "Mary-Jane O'Connor", "José Saramago", "J. R. R. Tolkien", "Ursula K. Le Guin",
        )
    }

    @Test
    fun `english credit roles and parenthetical aliases are distinct from metadata`() {
        assertNames(
            "Daniel Kahneman (author); translated by 胡晓姣 / 刘慈欣 (Liu Cixin)",
            "Daniel Kahneman", "胡晓姣", "刘慈欣", "Liu Cixin",
        )
    }

    @Test
    fun `chinese roles with or without spaces do not become search terms`() {
        assertNames("张三著；李四编译；王五 译；主编：赵六；钱七等", "张三", "李四", "王五", "赵六", "钱七")
        assertNames("作者：张三；譯者：李四", "张三", "李四")
        assertNames("谢译；郑和；王著", "谢译", "郑和", "王著")
    }

    @Test
    fun `line breaks and repeated names preserve original offsets`() {
        assertNames(" 张三\n李四\r\n张三 ", "张三", "李四", "张三")
    }

    @Test
    fun `empty unknown and label only fields offer no search links`() {
        listOf("", " ", "[美]", "（译者）", "作者不详", "佚名", "Unknown", "；、/").forEach {
            assertTrue(ContributorNames.parse(it).isEmpty(), it)
        }
    }

    private fun assertNames(raw: String, vararg expected: String): List<ContributorName> {
        val links = ContributorNames.parse(raw)
        assertEquals(expected.toList(), links.map { it.query }, raw)
        assertEquals(expected.toList(), links.map { raw.substring(it.start, it.end) }, "Links must cover only the name")
        assertTrue(links.zipWithNext().all { (a, b) -> a.end <= b.start })
        return links
    }

    private fun withoutNames(raw: String, links: List<ContributorName>): String = buildString {
        var cursor = 0
        links.forEach {
            append(raw.substring(cursor, it.start))
            cursor = it.end
        }
        append(raw.substring(cursor))
    }
}
