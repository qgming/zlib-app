package com.qgming.zlib

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.LocalZlibBookSnapshotsEnabled
import com.qgming.zlib.core.designsystem.LocalZlibCommentAvatarLoader
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.feature.detail.BookCommentDetailScreen
import com.qgming.zlib.feature.detail.BookCommentsScreen
import com.qgming.zlib.feature.detail.BookCommentsViewModel
import com.qgming.zlib.feature.detail.BookPreviewScreen
import com.qgming.zlib.feature.detail.DetailScreen
import com.qgming.zlib.feature.detail.DetailViewModel
import com.qgming.zlib.feature.search.SearchScreen
import com.qgming.zlib.navigation.BookDetailLauncher
import com.qgming.zlib.share.ShareImageActivity
import com.qgming.zlib.share.ShareImagePayload
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
private sealed interface DetailRoute : NavKey {
    @Serializable data object Book : DetailRoute
    @Serializable data class ContributorSearch(val name: String) : DetailRoute
    @Serializable data object Preview : DetailRoute
    @Serializable data class Comments(val bookId: String) : DetailRoute
    @Serializable data class Comment(val bookId: String, val commentId: String) : DetailRoute
}

@AndroidEntryPoint
class BookDetailActivity : ComponentActivity() {
    @Inject lateinit var domains: DomainRepository
    @Inject lateinit var sessions: SessionManager
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preview = BookDetailLauncher.preview(intent)
        if (preview == null) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        lifecycleScope.launch {
            // Usually already initialized; this also handles process recreation into details.
            domains.initialize()
            if (sessions.current() == null) {
                finish()
                return@launch
            }
            setContent {
                val settings by domains.settings.collectAsStateWithLifecycle()
                ZlibTheme(mode = settings.themeMode, bookGridColumns = settings.bookGridColumns) {
                    DetailNavigation(preview)
                }
            }
        }
    }

    @Composable
    private fun DetailNavigation(preview: Book) {
        val nav = rememberNavController<DetailRoute>(DetailRoute.Book)
        val comments = hiltViewModel<BookCommentsViewModel>()
        val detail = hiltViewModel<DetailViewModel>(key = preview.ref)
        LaunchedEffect(detail, preview.ref) { detail.load(preview.id, preview.hash) }
        val avatarLoader = remember { (application as ZlibApplication).commentAvatarLoader }
        val currentRoute = nav.backStack.lastOrNull()
        LaunchedEffect(comments, currentRoute) {
            when (currentRoute) {
                is DetailRoute.Comments -> comments.open(currentRoute.bookId)
                is DetailRoute.Comment -> comments.open(currentRoute.bookId, currentRoute.commentId)
                DetailRoute.Book -> Unit // Starts the preview after the resolved book is available.
                else -> comments.close()
            }
        }
        val scope = rememberCoroutineScope()
        val supportsSharedView = remember { BookDetailLauncher.isSupported() }
        val openBook: (ZlibBookOpenRequest) -> Unit = { request ->
            scope.launch { BookDetailLauncher.open(this@BookDetailActivity, request) }
        }
        CompositionLocalProvider(
            LocalZlibBookSnapshotsEnabled provides supportsSharedView,
            LocalZlibCommentAvatarLoader provides avatarLoader,
        ) {
            NavDisplay(
                navController = nav,
                effects = NavDisplayEffects(backdropColor = MiuixTheme.colorScheme.surface, blockInputDuringTransition = true),
            ) {
                entry<DetailRoute.Book> {
                    DetailScreen(
                        id = preview.id,
                        hash = preview.hash,
                        previewBook = preview,
                        onOpenBook = openBook,
                        onShare = { book ->
                            ShareImageActivity.launch(this@BookDetailActivity, ShareImagePayload.book(this@BookDetailActivity, book))
                        },
                        onBack = { finish() },
                        onPreview = {
                            if (nav.backStack.lastOrNull() == DetailRoute.Book) nav.push(DetailRoute.Preview)
                        },
                        commentsViewModel = comments,
                        viewModel = detail,
                        active = currentRoute == DetailRoute.Book,
                        onComments = { bookId ->
                            if (nav.backStack.lastOrNull() == DetailRoute.Book) nav.push(DetailRoute.Comments(bookId))
                        },
                        onOpenComment = { bookId, commentId ->
                            if (nav.backStack.lastOrNull() == DetailRoute.Book) nav.push(DetailRoute.Comment(bookId, commentId))
                        },
                        onSearch = { name ->
                            if (nav.backStack.lastOrNull() == DetailRoute.Book) nav.push(DetailRoute.ContributorSearch(name))
                        },
                    )
                }
                entry<DetailRoute.ContributorSearch> { route ->
                    SearchScreen(initialQuery = route.name, onBack = { nav.pop() }, onOpenBook = openBook)
                }
                entry<DetailRoute.Preview> {
                    BookPreviewScreen(preview.id, preview.hash, onBack = {
                        if (nav.backStack.lastOrNull() == DetailRoute.Preview) nav.pop()
                    })
                }
                entry<DetailRoute.Comments> { route ->
                    BookCommentsScreen(
                        viewModel = comments,
                        onBack = { if (nav.backStack.lastOrNull() == route) nav.pop() },
                        onOpenComment = { id ->
                            if (nav.backStack.lastOrNull() == route) nav.push(DetailRoute.Comment(route.bookId, id))
                        },
                    )
                }
                entry<DetailRoute.Comment> { route ->
                    BookCommentDetailScreen(route.commentId, comments, onBack = {
                        if (nav.backStack.lastOrNull() == route) nav.pop()
                    })
                }
            }
        }
    }

    override fun finish() {
        if (closing) return
        closing = true
        BookDetailLauncher.prepareReturn(this)
        super.finish()
    }

    override fun onDestroy() {
        if (isFinishing) BookDetailLauncher.release(intent)
        super.onDestroy()
    }
}
