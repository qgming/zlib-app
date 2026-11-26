package com.qgming.zlib.share

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.model.ShareImageContent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ShareImageUiState(
    val content: ShareImageContent? = null,
    val image: Bitmap? = null,
    val file: File? = null,
    val darkTheme: Boolean = false,
    val generating: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

internal class ShareImageViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val files = ShareImageFiles(File(context.cacheDir, ShareImageFiles.DIRECTORY))
    private val _state = MutableStateFlow(ShareImageUiState(darkTheme = savedState[DARK_THEME] ?: false))
    val state = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var generation: Job? = null
    private var themeInitialized: Boolean = savedState.contains(DARK_THEME)
    private var cachedContent: ShareImageContent? = null
    private var cachedCovers: List<Bitmap?>? = null
    private val savedStateHandle = savedState
    private val pendingSave = ShareImageSaveQueue(savedState[PENDING_DESTINATION]) { savedState[PENDING_DESTINATION] = it }

    fun open(content: ShareImageContent?, initialDarkTheme: Boolean) {
        val dark = if (themeInitialized) _state.value.darkTheme else initialDarkTheme.also {
            themeInitialized = true
            savedStateHandle[DARK_THEME] = it
        }
        if (_state.value.content == content && (_state.value.generating || _state.value.image != null)) return
        generation?.cancel()
        if (cachedContent != content) {
            clearCachedCovers()
        }
        _state.value = ShareImageUiState(content = content, darkTheme = dark)
        if (content == null) {
            _state.value = _state.value.copy(error = appText(TextR.string.text_share_image_unavailable))
            saveWhenReady()
        } else {
            generate(content, darkTheme = dark, reuseCovers = false)
        }
    }

    fun toggleTheme() {
        val current = state.value
        val content = current.content ?: return
        if (current.generating || current.working) return
        val nextDark = !current.darkTheme
        themeInitialized = true
        savedStateHandle[DARK_THEME] = nextDark
        generate(content, darkTheme = nextDark, reuseCovers = cachedContent == content && cachedCovers != null)
    }

    fun retry() {
        val current = state.value
        if (current.generating || current.working) return
        current.content?.let { content ->
            clearCachedCovers()
            generate(content, darkTheme = current.darkTheme, reuseCovers = false)
        }
    }

    private fun generate(content: ShareImageContent, darkTheme: Boolean, reuseCovers: Boolean) {
        generation?.cancel()
        val previousImage = _state.value.image.takeIf { reuseCovers }
        _state.value = ShareImageUiState(
            content = content,
            image = previousImage,
            darkTheme = darkTheme,
            generating = true,
        )
        generation = viewModelScope.launch {
            try {
                val covers = if (reuseCovers && cachedContent == content && cachedCovers != null) {
                    cachedCovers!!
                } else {
                    loadCovers(content).also { loaded ->
                        clearCachedCovers()
                        cachedContent = content
                        cachedCovers = loaded
                    }
                }
                val bitmap = withContext(Dispatchers.Default) {
                    ensureActive()
                    ShareImageRenderer(context).render(content, covers, darkTheme = darkTheme)
                }
                val file = withContext(Dispatchers.IO) {
                    ensureActive()
                    files.write { target ->
                        target.outputStream().buffered().use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("PNG encoding failed")
                        }
                    }
                }
                _state.value = ShareImageUiState(
                    content = content,
                    image = bitmap,
                    file = file,
                    darkTheme = darkTheme,
                )
                saveWhenReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = ShareImageUiState(
                    content = content,
                    darkTheme = darkTheme,
                    error = appText(TextR.string.text_share_image_failed),
                )
                saveWhenReady()
            }
        }
    }

    /**
     * Reuses the application's anonymous SingletonImageLoader (configured with CookieJar.NO_COOKIES
     * and SafeImageRedirectInterceptor) with memory & disk caches enabled so covers already shown
     * on detail/booklist screens load instantly without extra network traffic.
     */
    private suspend fun loadCovers(content: ShareImageContent): List<Bitmap?> = withContext(Dispatchers.IO) {
        val loader = SingletonImageLoader.get(context)
        val covers = arrayOfNulls<Bitmap>(content.books.size)
        val permits = Semaphore(3)
        withTimeoutOrNull(COVER_BUDGET_MILLIS) {
            coroutineScope {
                content.books.forEachIndexed { index, book ->
                    if (book.coverUrl.isNotEmpty()) launch {
                        permits.withPermit {
                            try {
                                val request = ImageRequest.Builder(context)
                                    .data(book.coverUrl)
                                    .size(600, 900)
                                    .allowHardware(false)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                                val decoded = loader.execute(request).image?.toBitmap()
                                // Copy into an owned software bitmap so cached references are never mutated or shared.
                                covers[index] = decoded?.let { bmp ->
                                    if (bmp.isRecycled) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Keep null slot when a cover fails to fetch.
                            }
                        }
                    }
                }
            }
        }
        covers.toList()
    }

    fun save(destination: Uri) {
        if (destination.scheme != "content") {
            _messages.trySend(appText(TextR.string.text_share_image_save_failed))
            return
        }
        if (pendingSave.enqueue(destination.toString())) saveWhenReady()
    }

    private fun saveWhenReady() {
        val current = state.value
        val ready = !current.generating && !current.working && (current.file != null || current.error != null)
        val destination = pendingSave.takeWhenReady(ready) ?: return
        _state.value = current.copy(working = true)
        viewModelScope.launch {
            val message = try {
                val outcome = withContext(Dispatchers.IO) { files.save(context, current.file, destination.toUri()) }
                appText(when (outcome) {
                    ShareImageSaveOutcome.Saved -> TextR.string.text_share_image_saved
                    ShareImageSaveOutcome.Failed -> TextR.string.text_share_image_save_failed
                    ShareImageSaveOutcome.Incomplete -> TextR.string.text_share_image_save_partial
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                appText(TextR.string.text_share_image_save_failed)
            } finally {
                pendingSave.finish()
                _state.value = _state.value.copy(working = false)
            }
            _messages.send(message)
        }
    }

    private fun clearCachedCovers() {
        cachedCovers?.forEach { bitmap ->
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
        cachedCovers = null
        cachedContent = null
    }

    override fun onCleared() {
        generation?.cancel()
        clearCachedCovers()
        super.onCleared()
    }

    private companion object {
        const val PENDING_DESTINATION = "share-image-destination"
        const val DARK_THEME = "share-image-dark-theme"
        const val COVER_BUDGET_MILLIS = 5_000L
    }
}
