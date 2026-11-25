package com.qgming.zlib.share

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.model.ShareImageContent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient

internal data class ShareImageUiState(
    val content: ShareImageContent? = null,
    val image: Bitmap? = null,
    val file: File? = null,
    val generating: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

internal class ShareImageViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val files = ShareImageFiles(File(context.cacheDir, ShareImageFiles.DIRECTORY))
    private val _state = MutableStateFlow(ShareImageUiState())
    val state = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var generation: Job? = null
    private val pendingSave = ShareImageSaveQueue(savedState[PENDING_DESTINATION]) { savedState[PENDING_DESTINATION] = it }

    fun open(content: ShareImageContent?) {
        if (_state.value.content == content && (_state.value.generating || _state.value.image != null)) return
        generation?.cancel()
        _state.value = ShareImageUiState(content = content)
        if (content == null) {
            _state.value = _state.value.copy(error = appText(TextR.string.text_share_image_unavailable))
        } else {
            generate(content)
        }
    }

    fun retry() {
        val current = state.value
        if (current.generating || current.working) return
        current.content?.let(::generate)
    }

    private fun generate(content: ShareImageContent) {
        generation?.cancel()
        _state.value = ShareImageUiState(content = content, generating = true)
        generation = viewModelScope.launch {
            try {
                val covers = loadCovers(content)
                val bitmap = withContext(Dispatchers.Default) {
                    ensureActive()
                    ShareImageRenderer(context).render(content, covers)
                }
                val file = withContext(Dispatchers.IO) {
                    ensureActive()
                    files.write { target ->
                        target.outputStream().buffered().use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("PNG encoding failed")
                        }
                    }
                }
                _state.value = ShareImageUiState(content = content, image = bitmap, file = file)
                saveWhenReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = ShareImageUiState(content = content, error = appText(TextR.string.text_share_image_failed))
            }
        }
    }

    private suspend fun loadCovers(content: ShareImageContent): List<Bitmap?> = withContext(Dispatchers.IO) {
        val anonymous = OkHttpClient.Builder()
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val loader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { anonymous })) }
            .diskCache(null)
            .build()
        try {
            val permits = Semaphore(2)
            coroutineScope {
                content.books.map { book -> async {
                    if (book.coverUrl.isEmpty()) return@async null
                    permits.withPermit {
                        try {
                            val request = ImageRequest.Builder(context).data(book.coverUrl)
                                .size(600, 900).allowHardware(false)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build()
                            loader.execute(request).image?.toBitmap()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                } }.awaitAll()
            }
        } finally {
            loader.shutdown()
            anonymous.connectionPool.evictAll()
            anonymous.dispatcher.executorService.shutdown()
        }
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
        val file = current.file ?: return
        val destination = pendingSave.takeWhenReady(!current.generating && !current.working) ?: return
        _state.value = current.copy(working = true)
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) { files.copyTo(context, file, destination.toUri()) }
                appText(TextR.string.text_share_image_saved)
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

    private companion object {
        const val PENDING_DESTINATION = "share-image-destination"
    }
}
