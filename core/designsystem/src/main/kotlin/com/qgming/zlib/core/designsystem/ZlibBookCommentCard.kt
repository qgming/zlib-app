package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.BookComment
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Contacts
import top.yukonga.miuix.kmp.icon.glass.LikeSelected
import top.yukonga.miuix.kmp.icon.glass.Messages
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The app provides a dedicated anonymous loader; never fall back to a session client. */
val LocalZlibCommentAvatarLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
fun ZlibBookCommentCard(
    comment: BookComment,
    replyCount: Int,
    modifier: Modifier = Modifier,
    replyTo: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    ZlibCard(modifier = modifier, onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CommentAvatar(comment.avatarUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        comment.author.ifBlank { stringResource(TextR.string.text_comment_author_unknown) },
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (comment.date.isNotBlank()) {
                        Text(comment.date, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (comment.text.isNotBlank() || replyTo != null) {
                if (onOpen != null) {
                    Text(comment.text, style = MiuixTheme.textStyles.body2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else {
                    val body = if (replyTo != null) replyBody(replyTo, comment.text) else AnnotatedString(comment.text)
                    SelectionContainer { Text(body, style = MiuixTheme.textStyles.body2) }
                }
            }
            CommentMetrics(replyCount, comment.likeCount)
        }
    }
}

@Composable
private fun CommentAvatar(url: String?) {
    val colors = MiuixTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(colors.onSurfaceVariantSummary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MiuixIcons.Glass.Normal.Contacts, contentDescription = null, modifier = Modifier.size(19.dp), tint = colors.onSurfaceVariantSummary)
        val loader = LocalZlibCommentAvatarLoader.current
        if (url != null && loader != null) {
            val context = LocalPlatformContext.current
            val request = remember(context, url) {
                ImageRequest.Builder(context).data(url).diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(request, contentDescription = null, imageLoader = loader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun replyBody(author: String, text: String): AnnotatedString {
    val mention = "@${author.ifBlank { stringResource(TextR.string.text_comment_author_unknown) }}"
    val template = stringResource(TextR.string.text_comment_reply_to, REPLY_MENTION)
    val tint = MiuixTheme.colorScheme.primary
    return remember(template, mention, text, tint) { annotateReplyBody(template, mention, text, tint) }
}

// The marker keeps the phrase and punctuation translatable as a unit without parsing the username.
internal fun annotateReplyBody(template: String, mention: String, text: String, tint: Color): AnnotatedString = buildAnnotatedString {
    append(template.substringBefore(REPLY_MENTION))
    withStyle(SpanStyle(color = tint)) { append(mention) }
    append(template.substringAfter(REPLY_MENTION, missingDelimiterValue = ""))
    append(text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentMetrics(replyCount: Int, likeCount: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CommentMetric(
            MiuixIcons.Glass.Normal.Messages,
            replyCount,
            pluralStringResource(TextR.plurals.comment_reply_count, replyCount, replyCount),
        )
        CommentMetric(
            MiuixIcons.Glass.Normal.LikeSelected,
            likeCount,
            pluralStringResource(TextR.plurals.comment_like_count, likeCount, likeCount),
        )
    }
}

@Composable
private fun CommentMetric(icon: ImageVector, count: Int, label: String) {
    val tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint)
        Text(stringResource(TextR.string.text_comment_metric_number, count), style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

internal const val REPLY_MENTION = "\uFFFC"
