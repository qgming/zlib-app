package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.update.AppUpdateRepository
import com.qgming.zlib.core.model.AppRelease
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReleaseHistoryState(val loading: Boolean = false, val loaded: Boolean = false, val releases: List<AppRelease> = emptyList(), val failed: Boolean = false)

@HiltViewModel
class ReleaseHistoryViewModel @Inject constructor(private val repository: AppUpdateRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReleaseHistoryState())
    val state = _state.asStateFlow()
    fun load() {
        if (_state.value.loading || _state.value.loaded) return
        _state.value = ReleaseHistoryState(loading = true)
        viewModelScope.launch {
            _state.value = when (val result = repository.releases()) {
                is AppResult.Success -> ReleaseHistoryState(loaded = true, releases = result.value)
                is AppResult.Failure -> ReleaseHistoryState(failed = true)
            }
        }
    }
}
