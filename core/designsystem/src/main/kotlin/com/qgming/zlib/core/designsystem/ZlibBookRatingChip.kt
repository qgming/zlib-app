package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.Book
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.StarFill
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BookRatings(val interest: String?, val quality: String?) {
    val text: String get() = "${interest ?: "—"} / ${quality ?: "—"}"
}

internal fun Book.ratings(locale: Locale): BookRatings? {
    fun Double?.score() = this?.takeIf { it.isFinite() && it >= 0 }?.let { "%.1f".format(locale, it) }
    val interest = interestScore.score()
    val quality = qualityScore.score()
    return if (interest == null && quality == null) null else BookRatings(interest, quality)
}

@Composable
internal fun ZlibBookRatingChip(
    book: Book,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    background: Color = MiuixTheme.colorScheme.surfaceContainer,
) {
    val locale = LocalConfiguration.current.locales[0]
    val ratings = remember(book, locale) { book.ratings(locale) } ?: return
    val dark = zlibIsDark()
    val yellow = if (dark) Color(0xFFFFE066) else Color(0xFFD6B300)
    val green = if (dark) Color(0xFF6BD390) else Color(0xFF1F743A)
    val neutral = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val unavailable = stringResource(TextR.string.text_rating_unavailable)
    val description = "${stringResource(TextR.string.text_interest_rating)}: ${ratings.interest ?: unavailable}, " +
        "${stringResource(TextR.string.text_download_quality)}: ${ratings.quality ?: unavailable}"
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(percent = 50))
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MiuixIcons.Glass.Normal.StarFill,
            contentDescription = null,
            modifier = Modifier.size(if (compact) 14.dp else 15.dp),
            tint = yellow,
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (ratings.interest != null) yellow else neutral)) { append(ratings.interest ?: "—") }
                withStyle(SpanStyle(color = neutral)) { append(" / ") }
                withStyle(SpanStyle(color = if (ratings.quality != null) green else neutral)) { append(ratings.quality ?: "—") }
            },
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
