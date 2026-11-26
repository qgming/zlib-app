package com.qgming.zlib

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import com.qgming.zlib.core.data.download.DownloadNavigation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qgming.zlib.ui.ZlibRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val downloadRequest = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        downloadRequest.intValue = savedInstanceState?.getInt(STATE_DOWNLOAD_REQUEST, 0) ?: 0
        acceptNotification(intent)
        setContent { ZlibRoot(downloadRequest = downloadRequest.intValue) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_DOWNLOAD_REQUEST, downloadRequest.intValue)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotification(intent)
    }

    private fun acceptNotification(intent: Intent?) {
        if (intent?.getStringExtra(DownloadNavigation.EXTRA_DESTINATION) == DownloadNavigation.DOWNLOADS) {
            downloadRequest.intValue++
            intent.removeExtra(DownloadNavigation.EXTRA_DESTINATION)
        }
    }

    private companion object {
        const val STATE_DOWNLOAD_REQUEST = "download_request"
    }
}
