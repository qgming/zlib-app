package com.qgming.zlib.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.LibraryRepository
import com.qgming.zlib.core.designsystem.userMessage
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
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    val books: StateFlow<List<Book>> = repository.downloaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun deleteLocal(book: Book) {
        viewModelScope.launch {
            when (val result = repository.deleteLocal(book)) {
                is AppResult.Success -> _events.send("已删除本地文件")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
