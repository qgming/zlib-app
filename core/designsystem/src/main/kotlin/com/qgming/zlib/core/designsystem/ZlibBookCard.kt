package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibBookCard(
    book: Book,
    onClick: () -> Unit,
    supportingText: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    ZlibCard(onClick = onClick, insideMargin = PaddingValues(8.dp)) {
        ZlibCover(
            url = book.coverUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
                maxLines = 2,
            )
            Text(
                text = book.authors,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
            Text(
                text = listOf(book.extension.uppercase(), book.year).filter(String::isNotBlank).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
        if (actions != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                content = actions,
            )
        }
    }
}
