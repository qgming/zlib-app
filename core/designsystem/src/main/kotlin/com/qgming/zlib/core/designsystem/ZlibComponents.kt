package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix card with the standard 16dp inside margin and full width on a page. */
@Composable
fun ZlibCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inside = PaddingValues(16.dp)
    if (onClick != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            insideMargin = inside,
            onClick = onClick,
            content = content,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            insideMargin = inside,
            content = content,
        )
    }
}

@Composable
fun ZlibSectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text = text, modifier = modifier)
}

/** Cover with a neutral placeholder so rows keep their height before Coil loads. */
@Composable
fun ZlibCover(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = MiuixIcons.ContactsBook,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSecondaryContainerVariant,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
