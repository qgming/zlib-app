package com.qgming.zlib.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.BookLanguages
import com.qgming.zlib.core.model.Book
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Data
import top.yukonga.miuix.kmp.icon.glass.Date
import top.yukonga.miuix.kmp.icon.glass.Document
import top.yukonga.miuix.kmp.icon.glass.Translate
import top.yukonga.miuix.kmp.icon.glass.StarFill
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COLLAPSED_DESCRIPTION_LINES = 5
private val DescriptionToggleSpacing = 4.dp
private val PillShape = RoundedCornerShape(percent = 50)

internal enum class BookDetailField(val labelRes: Int) {
    Rating(TextR.string.text_rating),
    Year(TextR.string.text_publication_year),
    Language(TextR.string.text_content_language),
    Format(TextR.string.text_current_format),
    FileSize(TextR.string.text_file_size),
}

internal data class BookDetailChip(val field: BookDetailField, val value: String)

internal enum class PublicationField(@StringRes val labelRes: Int, val valueOf: (Book) -> String) {
    Publisher(TextR.string.text_publisher, Book::publisher),
    Language(TextR.string.text_language, Book::language),
    Edition(TextR.string.text_edition, Book::edition),
    Pages(TextR.string.text_pages, { it.pages?.toString().orEmpty() }),
    Isbn10(TextR.string.text_isbn10, Book::isbn10),
    Isbn13(TextR.string.text_isbn13, Book::isbn13),
    Categories(TextR.string.text_categories, { book ->
        book.categories.map(String::trim).filter(String::isNotEmpty).joinToString(" / ")
    }),
}

internal fun Book.publicationFields(): List<Pair<PublicationField, String>> =
    PublicationField.entries.mapNotNull { field ->
        field.valueOf(this).trim().takeIf(String::isNotEmpty)?.let { field to it }
    }

val Book.hasPublicationInfo: Boolean
    get() = publicationFields().isNotEmpty()

internal fun Book.detailChips(locale: Locale, search: Boolean = false, languageLocale: Locale = locale): List<BookDetailChip> = buildList {
    ratings(locale)?.let { add(BookDetailChip(BookDetailField.Rating, it.text)) }
    year.trim().takeIf { it.isNotEmpty() && it.toDoubleOrNull() != 0.0 }?.let {
        add(BookDetailChip(BookDetailField.Year, it))
    }
    if (search) BookLanguages.displayName(language, languageLocale).takeIf(String::isNotEmpty)?.let {
        add(BookDetailChip(BookDetailField.Language, it))
    }
    extension.trim().takeIf(String::isNotEmpty)?.let { add(BookDetailChip(BookDetailField.Format, it.uppercase())) }
    sizeBytes?.takeIf { it >= 0 }?.let { add(BookDetailChip(BookDetailField.FileSize, formatBookFileSize(it, locale))) }
}

fun formatBookFileSize(bytes: Long, locale: Locale): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(locale, bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(locale, bytes / 1024.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZlibBookMetadataChips(
    book: Book,
    modifier: Modifier = Modifier.padding(top = 4.dp).widthIn(max = 520.dp).fillMaxWidth(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    chipColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    compact: Boolean = false,
    search: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val languageLocale = Locale.forLanguageTag(stringResource(TextR.string.text_content_language_locale))
    val chips = remember(book, locale, search, languageLocale) { book.detailChips(locale, search, languageLocale) }
    if (chips.isEmpty()) return

    val colors = MiuixTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        chips.forEach { chip ->
            if (chip.field == BookDetailField.Rating) {
                ZlibBookRatingChip(book, compact = compact, background = chipColor)
                return@forEach
            }
            val icons = MiuixIcons.Glass.Normal
            val icon = when (chip.field) {
                BookDetailField.Rating -> icons.StarFill
                BookDetailField.Year -> icons.Date
                BookDetailField.Language -> icons.Translate
                BookDetailField.Format -> icons.Document
                BookDetailField.FileSize -> icons.Data
            }
            val description = "${stringResource(chip.field.labelRes)}: ${chip.value}"
            Row(
                modifier = Modifier.widthIn(max = 224.dp)
                    .background(chipColor, PillShape)
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 15.dp),
                    tint = colors.onSurfaceVariantActions,
                )
                Text(
                    text = chip.value,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ZlibBookDescriptionCard(text: AnnotatedString, expanded: Boolean, onToggle: () -> Unit) {
    if (text.isBlank()) return
    var canToggle by remember(text) { mutableStateOf(false) }
    val bodyStyle = MiuixTheme.textStyles.body2
    ZlibCard(
        insideMargin = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (canToggle) DescriptionToggleSpacing else 16.dp),
    ) {
        Text(
            stringResource(TextR.string.text_description),
            modifier = Modifier.semantics { heading() },
            style = MiuixTheme.textStyles.title4,
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle.copy(lineHeight = bodyStyle.fontSize * 1.65f),
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DESCRIPTION_LINES,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    canToggle = layout.lineCount > COLLAPSED_DESCRIPTION_LINES || layout.didOverflowHeight
                },
            )
        }
        if (canToggle) {
            val colors = MiuixTheme.colorScheme
            val state = stringResource(if (expanded) R.string.zlib_expanded else R.string.zlib_collapsed)
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = DescriptionToggleSpacing)
                    .clip(PillShape).clickable(role = Role.Button, onClick = onToggle)
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp)
                    .semantics { stateDescription = state },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(if (expanded) TextR.string.text_collapse_description else TextR.string.text_expand_description),
                    style = MiuixTheme.textStyles.body2,
                    color = colors.primary,
                )
                Icon(
                    painter = painterResource(R.drawable.zlib_description_arrow_up),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else 180f),
                    tint = colors.primary,
                )
            }
        }
    }
}

@Composable
fun ZlibPublicationCard(book: Book, onSearchPublisher: (String) -> Unit) {
    val fields = remember(book) { book.publicationFields() }
    if (fields.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    ZlibCard {
        Text(
            stringResource(TextR.string.text_publication_information),
            modifier = Modifier.semantics { heading() },
            style = MiuixTheme.textStyles.title4,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEach { (field, value) ->
                val label = stringResource(field.labelRes)
                val isPublisher = field == PublicationField.Publisher
                val searchLabel = stringResource(TextR.string.text_search_publisher, value)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    if (isPublisher) {
                        Text(
                            text = buildAnnotatedString {
                                withLink(LinkAnnotation.Clickable(
                                    tag = searchLabel,
                                    styles = TextLinkStyles(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)),
                                    linkInteractionListener = { onSearchPublisher(value) },
                                )) { append(value) }
                            },
                            modifier = Modifier.weight(2f).semantics {
                                onClick(label = searchLabel) { onSearchPublisher(value); true }
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            textAlign = TextAlign.End,
                        )
                    } else {
                        Text(
                            text = value,
                            modifier = Modifier.weight(2f),
                            style = MiuixTheme.textStyles.footnote1,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}
