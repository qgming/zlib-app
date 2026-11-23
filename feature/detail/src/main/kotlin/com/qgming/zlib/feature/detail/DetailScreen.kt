package com.qgming.zlib.feature.detail

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ActivityNotFoundException
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.qgming.zlib.core.designsystem.ZlibBookInfoButton
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.EbookFiles
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DetailScreen(
    id: String,
    hash: String,
    onBack: () -> Unit,
    onSearchContributor: (String) -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    previewBook: Book? = null,
    viewModel: DetailViewModel = hiltViewModel(key = "$id:$hash"),
    favoriteViewModel: BookFavoriteViewModel = hiltViewModel(key = "$id:$hash:favorite"),
    similarViewModel: SimilarBooksViewModel = hiltViewModel(key = "$id:$hash:similar"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorite by favoriteViewModel.state.collectAsStateWithLifecycle()
    val similar by similarViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showInfo by rememberSaveable(id, hash) { mutableStateOf(false) }
    var notice by remember(id, hash) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, hash) { viewModel.load(id, hash) }
    LaunchedEffect(id, hash) { favoriteViewModel.load(id) }
    LaunchedEffect(state.book?.ref) { if (state.book != null) similarViewModel.load(id, hash) }
    LaunchedEffect(viewModel) { viewModel.events.collect { notice = it } }
    LaunchedEffect(viewModel, context) {
        viewModel.openFiles.collect { file ->
            try {
                val uri = file.uri.toUri()
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, EbookFiles.mimeType(file.book.extension))
                    clipData = ClipData.newRawUri(file.book.title, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: ActivityNotFoundException) { notice = appText(TextR.string.text_no_app_can_open_this_format_please_install_a_reader) }
            catch (_: SecurityException) { notice = appText(TextR.string.text_cannot_access_the_file_grant_download_folder_access_again) }
        }
    }
    LaunchedEffect(state.book) { if (state.book == null) notice = null }

    ZlibPage(
        title = stringResource(TextR.string.text_book_details),
        onBack = onBack,
        actions = {
            ZlibBookInfoButton(enabled = state.book != null, onClick = { showInfo = true })
        },
    ) { padding ->
        val book = state.book ?: previewBook?.takeIf { it.ref == "$id:$hash" }
        val error = state.error
        ZlibBookGrid(padding) {
            if (book == null) {
                fullWidthItem("detail-loading") {
                    if (error != null) {
                        ZlibStateCard(title = stringResource(TextR.string.text_cannot_load_book_details), message = error, onAction = viewModel::retry)
                    } else {
                        ZlibLoading()
                    }
                }
            } else {
                favorite.error?.let { message ->
                    fullWidthItem("favorite-error") {
                        ZlibStateCard(stringResource(TextR.string.text_cannot_sync_favorites), message, onAction = favoriteViewModel::retry)
                    }
                }
                fullWidthItem("detail-header") { BookHeader(book, onSearchContributor) }
                if (state.book == null) {
                    fullWidthItem("detail-error") {
                        if (error != null) {
                            ZlibStateCard(stringResource(TextR.string.text_cannot_load_book_details), error, onAction = viewModel::retry)
                        } else {
                            ZlibLoading()
                        }
                    }
                } else {
                    fullWidthItem("detail-actions") {
                        BookActions(
                            favorite = favorite,
                            message = notice,
                            downloading = state.enqueuing,
                            onDownload = viewModel::showFormats,
                            onFavorite = favoriteViewModel::toggle,
                        )
                    }
                }
                if (state.book != null && book.description.isNotBlank()) {
                    fullWidthItem("detail-description") { DescriptionCard(book.description) }
                }
                if (state.book != null) {
                    if (similar.loading || similar.error != null || similar.books.isNotEmpty()) {
                        fullWidthItem("similar-heading") { ZlibSectionTitle(stringResource(TextR.string.text_similar_books)) }
                    }
                    when {
                        similar.loading -> fullWidthItem("similar-loading") { ZlibLoading() }
                        similar.error != null -> fullWidthItem("similar-error") {
                            ZlibStateCard(stringResource(TextR.string.text_cannot_load_similar_books), similar.error.orEmpty(), onAction = similarViewModel::retry)
                        }
                        else -> items(similar.books, key = { "similar-${it.ref}" }) { similarBook ->
                            ZlibBookTile(book = similarBook, onClick = onOpenBook)
                        }
                    }
                }
            }
        }

        ZlibGlassBottomSheet(show = showInfo && state.book != null, title = stringResource(TextR.string.text_book_information), onDismiss = { showInfo = false }) {
            state.book?.let { BookInfoPanel(it) }
        }
        ZlibGlassBottomSheet(show = state.formats != null, title = stringResource(TextR.string.text_choose_download_format), onDismiss = viewModel::dismissFormats) {
            state.formats?.let { menu ->
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        menu.loading -> ZlibLoading()
                        menu.error != null -> ZlibStateCard(stringResource(TextR.string.text_cannot_load_other_formats), menu.error, onAction = viewModel::showFormats)
                    }
                    menu.options.forEach { format ->
                        val size = format.sizeText.ifBlank { format.sizeBytes?.sizeText().orEmpty() }
                        val duplicateExtension = menu.options.count { it.extension == format.extension } > 1
                        val label = listOfNotNull(
                            format.extension.uppercase(),
                            size.takeIf(String::isNotBlank),
                            stringResource(TextR.string.text_current_format).takeIf { format.ref == state.book?.ref },
                            stringResource(TextR.string.text_file, format.id).takeIf { duplicateExtension },
                        ).joinToString(" · ")
                        ZlibMenuAction(
                            text = label, enabled = !menu.loading && !state.enqueuing,
                            onClick = { viewModel.selectFormat(format.ref) },
                        )
                    }
                    if (!menu.loading && menu.options.isEmpty()) {
                        Text(stringResource(TextR.string.text_no_downloadable_formats), style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
        ZlibGlassBottomSheet(show = state.existing != null, title = stringResource(TextR.string.text_file_already_exists_locally), onDismiss = viewModel::dismissExisting) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZlibMenuAction(stringResource(TextR.string.text_open_existing_file), onClick = viewModel::openExisting)
                ZlibMenuAction(stringResource(TextR.string.text_download_again_and_keep_the_existing_file), onClick = viewModel::redownload)
            }
        }
    }
}

@Composable
private fun BookHeader(book: Book, onSearchContributor: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZlibCover(
            url = book.coverUrl,
            modifier = Modifier.size(width = 160.dp, height = 240.dp),
            contentScale = ContentScale.Fit,
            title = book.title,
            authors = book.authors,
        )
        Text(
            text = book.title,
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (book.subtitle.isNotBlank()) {
            Text(
                text = book.subtitle,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        if (book.authors.isNotBlank()) {
            ContributorText(book.authors, onSearchContributor)
        }
        book.rating?.let { rating ->
            Text(
                text = "★ ${"%.1f".format(rating)}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ContributorText(authors: String, onSearch: (String) -> Unit) {
    val names = remember(authors) { ContributorNames.parse(authors) }
    val blue = MiuixTheme.colorScheme.primary
    val links = buildAnnotatedString {
        append(authors)
        names.forEach { name ->
            addLink(
                LinkAnnotation.Clickable(
                    tag = appText(TextR.string.text_search_author, name.query),
                    styles = TextLinkStyles(style = SpanStyle(color = blue, fontWeight = FontWeight.Medium)),
                    linkInteractionListener = { onSearch(name.query) },
                ),
                name.start,
                name.end,
            )
        }
    }
    Text(links, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
}

@Composable
private fun BookActions(
    favorite: BookFavoriteState,
    message: String?,
    downloading: Boolean,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(stringResource(TextR.string.text_download_book)) }
            Button(
                onClick = onFavorite,
                enabled = favorite.saved != null && !favorite.busy,
                modifier = Modifier.weight(1f).semantics {
                    selected = favorite.saved == true
                    stateDescription = when {
                        favorite.busy -> appText(TextR.string.text_syncing_favorites)
                        favorite.saved == true -> appText(TextR.string.text_saved_tap_to_remove_from_favorites)
                        favorite.saved == false -> appText(TextR.string.text_not_saved)
                        else -> appText(TextR.string.text_favorite_status_unknown)
                    }
                },
                colors = if (favorite.saved == true) ButtonDefaults.buttonColors(
                    color = Color(0xFFE53935).copy(alpha = 0.1f),
                    contentColor = Color(0xFFE53935),
                ) else ButtonDefaults.buttonColors(),
            ) { Text(if (favorite.saved == true) stringResource(TextR.string.text_saved) else stringResource(TextR.string.text_add_to_favorites)) }
        }
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BookInfoPanel(book: Book) {
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            MetaRow(stringResource(TextR.string.text_publisher), book.publisher)
            MetaRow(stringResource(TextR.string.text_publication_year), book.year)
            MetaRow(stringResource(TextR.string.text_language), book.language)
            MetaRow(stringResource(TextR.string.text_edition), book.edition)
            MetaRow(stringResource(TextR.string.text_pages), book.pages?.toString().orEmpty())
            MetaRow("ISBN-10", book.isbn10)
            MetaRow("ISBN-13", book.isbn13)
            MetaRow(stringResource(TextR.string.text_categories), book.categories.joinToString(" / "))
        }
    }
}

@Composable
private fun DescriptionCard(description: String) {
    val formatted = remember(description) { AnnotatedString.fromHtml(BookDescription.html(description)) }
    if (formatted.isBlank()) return
    ZlibCard {
        Text(stringResource(TextR.string.text_description), style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                text = formatted,
                style = MiuixTheme.textStyles.body2.copy(lineHeight = MiuixTheme.textStyles.body2.fontSize * 1.65f),
            )
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(value, modifier = Modifier.weight(2f), style = MiuixTheme.textStyles.footnote1, textAlign = TextAlign.End)
    }
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
