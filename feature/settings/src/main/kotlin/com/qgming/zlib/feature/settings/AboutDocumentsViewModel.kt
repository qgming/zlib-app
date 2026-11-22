package com.qgming.zlib.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AboutLicenses(val project: String, val notice: String, val apache: String)

data class AboutDocumentsState(
    val licenses: AboutLicenses? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class AboutDocumentsViewModel internal constructor(private val readLicenses: suspend () -> AboutLicenses) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context) : this({
        withContext(Dispatchers.IO) {
            fun read(name: String): String = context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            AboutLicenses(read("LICENSE"), read("NOTICE"), read("APACHE-2.0.txt"))
        }
    })

    private val _state = MutableStateFlow(AboutDocumentsState())
    val state = _state.asStateFlow()

    fun loadLicenses() {
        if (_state.value.loading || _state.value.licenses != null) return
        _state.value = AboutDocumentsState(loading = true)
        viewModelScope.launch {
            try {
                val licenses = readLicenses()
                if (listOf(licenses.project, licenses.notice, licenses.apache).any(String::isBlank)) throw IOException("Empty license")
                _state.value = AboutDocumentsState(licenses = licenses)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                _state.value = AboutDocumentsState(failed = true)
            }
        }
    }
}
