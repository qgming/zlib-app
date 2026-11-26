package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class BulletItem(val indent: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class OrderedItem(val indent: Int, val marker: String, val content: List<MarkdownInline>) : MarkdownBlock
    data class BlockQuote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

internal sealed interface MarkdownInline {
    data class Plain(val text: String) : MarkdownInline
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline
    data class Code(val text: String) : MarkdownInline
    data class Link(val label: List<MarkdownInline>, val url: String) : MarkdownInline
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
private val HORIZONTAL_RULE_REGEX = Regex("""^\s*([-*_])(?:\s*\1){2,}\s*$""")
private val FENCE_REGEX = Regex("""^\s*(`{3,}|~{3,})\s*([\w.+-]*)\s*$""")
private val BULLET_REGEX = Regex("""^(\s*)[-*+]\s+(.+)$""")
private val ORDERED_REGEX = Regex("""^(\s*)(\d+[.)、])\s*(.+)$""")
private val BLOCKQUOTE_REGEX = Regex("""^\s*>\s?(.*)$""")

internal fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            index++
            continue
        }

        val fence = FENCE_REGEX.matchEntire(raw)
        if (fence != null) {
            val markerChar = fence.groupValues[1].first()
            val markerLen = fence.groupValues[1].length
            val language = fence.groupValues[2].takeIf { it.isNotBlank() }
            index++
            val codeLines = mutableListOf<String>()
            while (index < lines.size) {
                val close = lines[index].trim()
                if (close.length >= markerLen && close.all { it == markerChar }) {
                    index++
                    break
                }
                codeLines += lines[index]
                index++
            }
            blocks += MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n"))
            continue
        }

        val heading = HEADING_REGEX.matchEntire(trimmed)
        if (heading != null) {
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, parseMarkdownInlines(heading.groupValues[2]))
            index++
            continue
        }

        if (HORIZONTAL_RULE_REGEX.matches(raw)) {
            blocks += MarkdownBlock.HorizontalRule
            index++
            continue
        }

        if (BLOCKQUOTE_REGEX.matches(raw)) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size) {
                val match = BLOCKQUOTE_REGEX.matchEntire(lines[index]) ?: break
                quoteLines += match.groupValues[1]
                index++
            }
            val nested = parseMarkdownBlocks(quoteLines.joinToString("\n"))
            if (nested.isNotEmpty()) blocks += MarkdownBlock.BlockQuote(nested)
            continue
        }

        val bullet = BULLET_REGEX.matchEntire(raw)
        if (bullet != null) {
            val indent = computeIndentLevel(bullet.groupValues[1])
            val parts = mutableListOf(bullet.groupValues[2].trim())
            index++
            while (index < lines.size && isListContinuation(lines[index])) {
                parts += lines[index].trim()
                index++
            }
            blocks += MarkdownBlock.BulletItem(indent, parseMarkdownInlines(joinContinuation(parts)))
            continue
        }

        val ordered = ORDERED_REGEX.matchEntire(raw)
        if (ordered != null) {
            val indent = computeIndentLevel(ordered.groupValues[1])
            val marker = normalizeOrderedMarker(ordered.groupValues[2])
            val parts = mutableListOf(ordered.groupValues[3].trim())
            index++
            while (index < lines.size && isListContinuation(lines[index])) {
                parts += lines[index].trim()
                index++
            }
            blocks += MarkdownBlock.OrderedItem(indent, marker, parseMarkdownInlines(joinContinuation(parts)))
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            if (paragraphLines.isNotEmpty() && startsSpecialBlock(line)) break
            paragraphLines += line.trim()
            index++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(parseMarkdownInlines(paragraphLines.joinToString("\n")))
        }
    }
    return blocks
}

private fun startsSpecialBlock(line: String): Boolean {
    val trimmed = line.trim()
    return FENCE_REGEX.matches(line) ||
        HEADING_REGEX.matches(trimmed) ||
        HORIZONTAL_RULE_REGEX.matches(line) ||
        BLOCKQUOTE_REGEX.matches(line) ||
        BULLET_REGEX.matches(line) ||
        ORDERED_REGEX.matches(line)
}

private fun isListContinuation(line: String): Boolean {
    if (line.isBlank()) return false
    if (!line.first().isWhitespace()) return false
    return !startsSpecialBlock(line)
}

private fun joinContinuation(parts: List<String>): String = parts.joinToString(" ")

private fun computeIndentLevel(leadingSpaces: String): Int {
    var spaces = 0
    for (ch in leadingSpaces) {
        spaces += if (ch == '\t') 2 else 1
    }
    return (spaces / 2).coerceAtMost(3)
}

private fun normalizeOrderedMarker(raw: String): String =
    if (raw.endsWith('、')) raw else if (raw.endsWith(')')) raw else "${raw.trimEnd('.')}."

internal fun parseMarkdownInlines(text: String): List<MarkdownInline> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            result += MarkdownInline.Plain(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val ch = text[i]

        // Backslash escape for ASCII punctuation
        if (ch == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!~|>") {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // Inline code: `code`
        if (ch == '`') {
            var tickCount = 1
            while (i + tickCount < text.length && text[i + tickCount] == '`') tickCount++
            val delimiter = "`".repeat(tickCount)
            val end = text.indexOf(delimiter, i + tickCount)
            if (end > i + tickCount) {
                flushPlain()
                val rawCode = text.substring(i + tickCount, end)
                val cleaned = if (rawCode.length >= 2 && rawCode.startsWith(' ') && rawCode.endsWith(' ') && rawCode.any { it != ' ' }) {
                    rawCode.substring(1, rawCode.length - 1)
                } else {
                    rawCode
                }
                result += MarkdownInline.Code(cleaned)
                i = end + tickCount
                continue
            }
        }

        // Link: [label](url)
        if (ch == '[') {
            val closeBracket = findMatchingBracket(text, i)
            if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = findMatchingParen(text, closeBracket + 1)
                if (closeParen != -1) {
                    val rawLabel = text.substring(i + 1, closeBracket)
                    val rawUrl = text.substring(closeBracket + 2, closeParen).trim()
                    if (rawLabel.isNotEmpty() && isSafeMarkdownUrl(rawUrl)) {
                        flushPlain()
                        result += MarkdownInline.Link(parseMarkdownInlines(rawLabel), rawUrl)
                        i = closeParen + 1
                        continue
                    }
                }
            }
        }

        // Bold: **text** or __text__
        if ((ch == '*' || ch == '_') && i + 1 < text.length && text[i + 1] == ch) {
            val delimiter = "$ch$ch"
            val end = findClosingDelimiter(text, i + 2, delimiter)
            if (end != -1) {
                val inner = text.substring(i + 2, end)
                if (inner.isNotBlank()) {
                    flushPlain()
                    result += MarkdownInline.Bold(parseMarkdownInlines(inner))
                    i = end + 2
                    continue
                }
            }
        }

        // Strikethrough: ~~text~~
        if (ch == '~' && i + 1 < text.length && text[i + 1] == '~') {
            val end = findClosingDelimiter(text, i + 2, "~~")
            if (end != -1) {
                val inner = text.substring(i + 2, end)
                if (inner.isNotBlank()) {
                    flushPlain()
                    result += MarkdownInline.Strikethrough(parseMarkdownInlines(inner))
                    i = end + 2
                    continue
                }
            }
        }

        // Italic: *text* or _text_ (avoid splitting snake_case words)
        if (ch == '*' || (ch == '_' && !isWordChar(text.getOrNull(i - 1)))) {
            val delimiter = ch.toString()
            val end = findClosingItalic(text, i + 1, ch)
            if (end != -1) {
                val inner = text.substring(i + 1, end)
                if (inner.isNotBlank() && !inner.startsWith(' ') && !inner.endsWith(' ')) {
                    flushPlain()
                    result += MarkdownInline.Italic(parseMarkdownInlines(inner))
                    i = end + delimiter.length
                    continue
                }
            }
        }

        plain.append(ch)
        i++
    }

    flushPlain()
    return result
}

private fun findMatchingBracket(text: String, openIndex: Int): Int {
    var depth = 0
    var i = openIndex
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '[' -> {
                depth++
                i++
            }
            ']' -> {
                depth--
                if (depth == 0) return i
                i++
            }
            else -> i++
        }
    }
    return -1
}

private fun findMatchingParen(text: String, openIndex: Int): Int {
    var depth = 0
    var i = openIndex
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '(' -> {
                depth++
                i++
            }
            ')' -> {
                depth--
                if (depth == 0) return i
                i++
            }
            else -> i++
        }
    }
    return -1
}

private fun findClosingDelimiter(text: String, start: Int, delimiter: String): Int {
    var i = start
    while (i <= text.length - delimiter.length) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (text.startsWith(delimiter, i)) return i
        i++
    }
    return -1
}

private fun findClosingItalic(text: String, start: Int, ch: Char): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (text[i] == ch) {
            val next = text.getOrNull(i + 1)
            if (next == ch) {
                // Skip bold delimiter inside italic
                i += 2
                continue
            }
            if (ch == '_' && isWordChar(next)) {
                i++
                continue
            }
            return i
        }
        i++
    }
    return -1
}

private fun isWordChar(ch: Char?): Boolean = ch != null && (ch.isLetterOrDigit())

internal fun isSafeMarkdownUrl(url: String): Boolean {
    val trimmed = url.trim()
    return (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) &&
        trimmed.none(Char::isWhitespace)
}

@Composable
fun ZlibMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = remember(onOpenUrl, uriHandler) {
        { url ->
            if (onOpenUrl != null) {
                onOpenUrl(url)
            } else {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val isDark = zlibIsDark()
    val codeBackground = if (isDark) Color(0xFF2B2B30) else Color(0xFFEBECEF)
    val quoteBarColor = colors.primary.copy(alpha = 0.65f)
    val quoteBackground = colors.surfaceContainer.copy(alpha = if (isDark) 0.55f else 0.7f)
    val dividerColor = colors.outline.copy(alpha = 0.4f)
    val bodyStyle = textStyles.body2.copy(lineHeight = 24.sp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockView(
                block = block,
                isFirst = index == 0,
                bodyStyle = bodyStyle,
                codeBackground = codeBackground,
                quoteBarColor = quoteBarColor,
                quoteBackground = quoteBackground,
                dividerColor = dividerColor,
                onOpenUrl = openUrl,
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    isFirst: Boolean,
    bodyStyle: TextStyle,
    codeBackground: Color,
    quoteBarColor: Color,
    quoteBackground: Color,
    dividerColor: Color,
    onOpenUrl: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val linkColor = colors.primary
    val codeTextColor = colors.onSurface

    when (block) {
        is MarkdownBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> textStyles.title3.copy(fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                2 -> textStyles.headline2.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                3 -> textStyles.body1.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                else -> textStyles.body2.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
            }
            val topPadding = when {
                isFirst -> 0.dp
                block.level <= 2 -> 6.dp
                else -> 4.dp
            }
            val text = remember(block.content, linkColor, codeBackground, codeTextColor) {
                buildMarkdownAnnotatedString(block.content, linkColor, codeBackground, codeTextColor, onOpenUrl)
            }
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth().padding(top = topPadding),
                style = headingStyle,
            )
        }

        is MarkdownBlock.Paragraph -> {
            val text = remember(block.content, linkColor, codeBackground, codeTextColor) {
                buildMarkdownAnnotatedString(block.content, linkColor, codeBackground, codeTextColor, onOpenUrl)
            }
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle,
            )
        }

        is MarkdownBlock.BulletItem -> {
            val text = remember(block.content, linkColor, codeBackground, codeTextColor) {
                buildMarkdownAnnotatedString(block.content, linkColor, codeBackground, codeTextColor, onOpenUrl)
            }
            val bulletGlyph = if (block.indent == 0) "•" else "◦"
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = bulletGlyph,
                    style = bodyStyle.copy(fontWeight = FontWeight.Bold),
                    color = colors.primary,
                )
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = bodyStyle,
                )
            }
        }

        is MarkdownBlock.OrderedItem -> {
            val text = remember(block.content, linkColor, codeBackground, codeTextColor) {
                buildMarkdownAnnotatedString(block.content, linkColor, codeBackground, codeTextColor, onOpenUrl)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = block.marker,
                    style = bodyStyle.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = bodyStyle,
                )
            }
        }

        is MarkdownBlock.BlockQuote -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(quoteBackground)
                    .drawBehind {
                        val barWidth = 3.dp.toPx()
                        drawLine(
                            color = quoteBarColor,
                            start = Offset(barWidth / 2f, 0f),
                            end = Offset(barWidth / 2f, size.height),
                            strokeWidth = barWidth,
                        )
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.blocks.forEachIndexed { childIndex, child ->
                    MarkdownBlockView(
                        block = child,
                        isFirst = childIndex == 0,
                        bodyStyle = bodyStyle.copy(color = colors.onSurfaceVariantSummary),
                        codeBackground = codeBackground,
                        quoteBarColor = quoteBarColor,
                        quoteBackground = quoteBackground,
                        dividerColor = dividerColor,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(codeBackground)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = block.code,
                    style = textStyles.footnote1.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                    ),
                    color = codeTextColor,
                )
            }
        }

        MarkdownBlock.HorizontalRule -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .background(dividerColor),
            )
        }
    }
}

internal fun buildMarkdownAnnotatedString(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    codeBackground: Color,
    codeTextColor: Color,
    onOpenUrl: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    fun appendInlines(nodes: List<MarkdownInline>) {
        for (node in nodes) {
            when (node) {
                is MarkdownInline.Plain -> append(node.text)
                is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInlines(node.children)
                }
                is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlines(node.children)
                }
                is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlines(node.children)
                }
                is MarkdownInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        color = codeTextColor,
                    ),
                ) {
                    append(" ")
                    append(node.text)
                    append(" ")
                }
                is MarkdownInline.Link -> withLink(
                    LinkAnnotation.Clickable(
                        tag = node.url,
                        styles = TextLinkStyles(
                            SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = { onOpenUrl(node.url) },
                    ),
                ) {
                    appendInlines(node.label)
                }
            }
        }
    }
    appendInlines(inlines)
}
