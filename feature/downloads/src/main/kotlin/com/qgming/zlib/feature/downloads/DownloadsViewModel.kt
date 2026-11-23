package com.qgming.zlib.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DownloadItem
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
) : ViewModel() {

    val queue: StateFlow<List<DownloadItem>?> = repository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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

    fun setDirectory(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val result = repository.setDownloadDirectory(uri)) {
                is AppResult.Success -> _events.send(if (result.value.pausedTasks > 0) {
                    "下载目录已保存，${result.value.pausedTasks} 个任务已暂停，可手动继续"
                } else "下载目录已保存")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
