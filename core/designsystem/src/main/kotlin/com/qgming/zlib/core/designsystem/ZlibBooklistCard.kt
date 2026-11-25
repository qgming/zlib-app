package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.CloudBooklist
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibBooklistCard(
    booklist: CloudBooklist,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ZlibCard(modifier = modifier, onClick = onClick, insideMargin = PaddingValues(12.dp), cornerRadius = 24.dp) {
        Row(
            modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(BooklistCover.COUNT) { index ->
                Box(
                    modifier = Modifier.weight(1f).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f)),
                ) {
                    booklist.covers.getOrNull(index)?.let { cover ->
                        AsyncImage(
                            model = cover.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = booklist.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                booklist.bookCount?.let { count ->
                    Text(
                        text = pluralStringResource(TextR.plurals.booklist_book_count, count, count),
                        modifier = Modifier.padding(top = 2.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
