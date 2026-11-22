package com.qgming.zlib.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.data.download.LocalFilesRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val localFiles: LocalFilesRepository,
) : ViewModel() {

    val queue: StateFlow<List<DownloadItem>?> = repository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val files: StateFlow<List<Book>?> = localFiles.downloaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _shareEvents = Channel<Book>(Channel.BUFFERED)
    val shareEvents = _shareEvents.receiveAsFlow()

    fun shareLocal(book: Book) {
        viewModelScope.launch {
            when (val result = localFiles.shareUri(book)) {
                is AppResult.Success -> _shareEvents.send(book.copy(localUri = result.value))
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }

    fun deleteLocal(book: Book) {
        viewModelScope.launch {
            when (val result = localFiles.deleteLocal(book)) {
                is AppResult.Success -> _events.send("已删除本机文件")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }

    fun pause(id: String) {
        viewModelScope.launch { repository.pause(id) }
    }

    fun resume(id: String) {
        viewModelScope.launch {
            when (val result = repository.resume(id)) {
                is AppResult.Failure -> _events.send(result.error.userMessage())
                is AppResult.Success -> Unit
            }
        }
    }

    fun retry(id: String) {
        viewModelScope.launch {
            when (val result = repository.retry(id)) {
                is AppResult.Failure -> _events.send(result.error.userMessage())
                is AppResult.Success -> Unit
            }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch { repository.cancel(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }
}
