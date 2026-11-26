package com.qgming.zlib.feature.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.designsystem.ZlibBookCommentCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.userMessage

@Composable
fun BookCommentsScreen(viewModel: BookCommentsViewModel, onBack: () -> Unit, onOpenComment: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screenTitle = stringResource(TextR.string.text_book_comments)
    ZlibPage(title = screenTitle, onBack = onBack) { padding ->
        key(state.dataset) {
            ZlibCardList(padding = padding, state = rememberLazyListState()) {
                val mainComments = state.threads.mainComments
                items(mainComments, key = { "comment-${it.id}" }) { comment ->
                    ZlibBookCommentCard(comment, state.threads.replyCount(comment.id), onOpen = { onOpenComment(comment.id) })
                }
                if (!state.loading && state.error == null && mainComments.isEmpty()) {
                    commentNotice(if (state.page?.disabled == true) TextR.string.text_comments_disabled else TextR.string.text_no_comments)
                }
                pagingFooter(state, viewModel, screenTitle, mainComments.isNotEmpty(), TextR.string.text_load_more_comments)
            }
        }
    }
}

@Composable
fun BookCommentDetailScreen(commentId: String, viewModel: BookCommentsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screenTitle = stringResource(TextR.string.text_book_comment_detail)
    val main = state.threads.mainComment(commentId)
    val replies = remember(state.threads, commentId) { state.threads.replies(commentId) }
    ZlibPage(title = screenTitle, onBack = onBack) { padding ->
        key(state.dataset, commentId) {
            ZlibCardList(padding = padding) {
                if (main != null) {
                    item("main-comment") { ZlibBookCommentCard(main, state.threads.replyCount(main.id)) }
                    if (replies.isNotEmpty() || state.page?.nextPage != null) {
                        item("replies-title") { ZlibSectionTitle(stringResource(TextR.string.text_comment_replies)) }
                    }
                    items(replies, key = { "reply-${it.comment.id}" }) { reply ->
                        ZlibBookCommentCard(
                            comment = reply.comment,
                            replyCount = state.threads.replyCount(reply.comment.id),
                            replyTo = reply.replyTo?.author,
                        )
                    }
                    if (replies.isEmpty() && !state.loading && state.error == null && state.page?.nextPage == null) {
                        commentNotice(TextR.string.text_no_comment_replies)
                    }
                } else if (!state.loading && state.error == null && state.page?.nextPage == null) {
                    commentNotice(if (state.page?.disabled == true) TextR.string.text_comments_disabled else TextR.string.text_book_comment_unavailable)
                }
                pagingFooter(state, viewModel, screenTitle, main != null, TextR.string.text_load_more_comment_replies)
            }
        }
    }
}

private fun LazyListScope.commentNotice(message: Int) {
    item("notice") { ZlibSectionTitle(stringResource(message), Modifier.padding(vertical = 8.dp)) }
}

private fun LazyListScope.pagingFooter(
    state: BookCommentsState,
    viewModel: BookCommentsViewModel,
    title: String,
    hasContent: Boolean,
    loadMoreLabel: Int,
) {
    val error = state.error
    when {
        state.loading -> item("loading") { ZlibLoading() }
        error != null -> item("error") {
            ZlibStateCard(
                title = if (hasContent) stringResource(TextR.string.text_cannot_load_more_comments)
                    else stringResource(TextR.string.text_cannot_load, title),
                message = error.userMessage(),
                onAction = if (error == AppError.NotLoggedIn) null else viewModel::retry,
            )
        }
        state.page?.nextPage != null -> item("more") {
            ZlibMenuAction(stringResource(loadMoreLabel), onClick = viewModel::loadMore)
        }
    }
}
