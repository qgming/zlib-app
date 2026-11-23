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

data class AboutLicenses(
    val project: String,
    val apache: String,
    val heartleo: String,
    val materialkolor: String,
    val jakartaNotice: String,
    val sharedView: String,
)

data class AboutDocumentsState(
    val licenses: AboutLicenses? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val entries: List<AboutLicenseEntry> = emptyList(),
)

@HiltViewModel
class AboutDocumentsViewModel internal constructor(private val readLicenses: suspend () -> AboutLicenses) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context) : this({
        withContext(Dispatchers.IO) {
            fun read(name: String): String = context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            AboutLicenses(
                read("LICENSE"), read("APACHE-2.0.txt"), read("HEARTLEO-MIT.txt"),
                read("MATERIALKOLOR-MIT.txt"), read("JAKARTA-NOTICE.txt"), read("SHAREDVIEW-NOTICE.txt"),
            )
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
                if (listOf(licenses.project, licenses.apache, licenses.heartleo, licenses.materialkolor, licenses.jakartaNotice, licenses.sharedView).any(String::isBlank)) {
                    throw IOException("Empty license")
                }
                _state.value = AboutDocumentsState(licenses = licenses, entries = licenses.entries())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                _state.value = AboutDocumentsState(failed = true)
            }
        }
    }
}
