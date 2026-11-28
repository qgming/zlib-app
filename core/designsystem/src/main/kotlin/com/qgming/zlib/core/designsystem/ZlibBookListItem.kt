package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibBookListItem(
    book: Book,
    onClick: (ZlibBookOpenRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = if (LocalZlibBookSnapshotsEnabled.current) rememberCoverSource() else null
    val colors = MiuixTheme.colorScheme
    val clickLabel = stringResource(TextR.string.text_view_book_details)
    val openBook = { onClick(ZlibBookOpenRequest(book, source)) }
    val chipColor = if (zlibIsDark()) colors.onSurfaceVariantSummary.copy(alpha = 0.16f) else colors.surface
    ZlibCard(
        modifier = modifier.semantics {
            onClick(label = clickLabel) {
                openBook()
                true
            }
        },
        onClick = openBook,
        insideMargin = PaddingValues(12.dp),
        cornerRadius = 24.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ZlibCover(
                url = book.coverUrl,
                modifier = Modifier
                    .width(76.dp)
                    .aspectRatio(2f / 3f)
                    .then(source?.modifier ?: Modifier),
                contentScale = ContentScale.Crop,
                title = book.title,
                authors = book.authors,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = book.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val publisher = book.publisher.trim()
                if (publisher.isNotEmpty()) {
                    Text(
                        text = publisher,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.authors.isNotBlank()) {
                    Text(
                        text = book.authors,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ZlibBookMetadataChips(
                    book = book,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                    chipColor = chipColor,
                    compact = true,
                    search = true,
                )
            }
        }
    }
}
