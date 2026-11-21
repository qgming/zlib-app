package com.qgming.zlib.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.LibraryRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySection(val title: String) {
    Downloaded("已下载"),
    Saved("收藏"),
    History("历史"),
}

internal enum class LibraryRequest { Initial, Refresh, Append }

internal data class RemoteLibraryState(
    val page: BookPage? = null,
    val request: LibraryRequest? = null,
    val error: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    val books: StateFlow<List<Book>?> = repository.downloaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _remote = MutableStateFlow<Map<LibrarySection, RemoteLibraryState>>(emptyMap())
    internal val remote = _remote.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onSectionShown(section: LibrarySection) {
        if (section !in _remote.value) load(section, LibraryRequest.Initial)
    }

    fun refresh(section: LibrarySection) = load(section, LibraryRequest.Refresh)

    fun loadMore(section: LibrarySection) = load(section, LibraryRequest.Append)

    private fun load(section: LibrarySection, request: LibraryRequest) {
        if (section == LibrarySection.Downloaded) return
        val current = _remote.value[section] ?: RemoteLibraryState()
        if (current.request != null) return
        val requestedPage = if (request == LibraryRequest.Append) {
            current.page?.takeIf { it.hasNext }?.let { it.page + 1 } ?: return
        } else {
            1
        }

        updateRemote(section, current.copy(request = request, error = null))
        viewModelScope.launch {
            val result = when (section) {
                LibrarySection.Saved -> repository.saved(requestedPage)
                LibrarySection.History -> repository.history(requestedPage)
                LibrarySection.Downloaded -> return@launch
            }
            val updated = when (result) {
                is AppResult.Success -> {
                    val previousBooks = if (request == LibraryRequest.Append) current.page?.books.orEmpty() else emptyList()
                    val page = result.value
                    RemoteLibraryState(page = page.copy(books = (previousBooks + page.books).distinctBy(Book::ref)))
                }

                is AppResult.Failure -> current.copy(error = result.error.userMessage())
            }
            updateRemote(section, updated)
        }
    }

    private fun updateRemote(section: LibrarySection, state: RemoteLibraryState) {
        _remote.update { it + (section to state) }
    }

    fun deleteLocal(book: Book) {
        viewModelScope.launch {
            when (val result = repository.deleteLocal(book)) {
                is AppResult.Success -> _events.send("已删除本地文件")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
