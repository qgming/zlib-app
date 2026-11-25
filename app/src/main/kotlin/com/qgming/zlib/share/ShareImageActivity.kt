package com.qgming.zlib.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibShareImageContent
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState
import com.qgming.zlib.core.model.ShareImageContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShareImageActivity : ComponentActivity() {
    @Inject lateinit var settingsStore: SettingsStore
    private val viewModel: ShareImageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val content = ShareImagePayload.read(intent)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(content) { viewModel.open(content) }
            ZlibTheme(mode = settings.themeMode, bookGridColumns = settings.bookGridColumns) {
                val toast = rememberZlibToastHostState()
                ZlibToastHost(toast)
                ZlibToastHost(viewModel.messages)
                val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
                    uri?.let(viewModel::save)
                }
                val bitmap = state.image
                val preview = remember(bitmap) { bitmap?.asImageBitmap() }
                ZlibPage(title = stringResource(TextR.string.text_share_image_title), onBack = { finish() }) { padding ->
                    ZlibShareImageContent(
                        image = preview,
                        generating = state.generating,
                        working = state.working,
                        error = state.error,
                        onRetry = viewModel::retry,
                        onSave = {
                            state.content?.let { payload ->
                                try {
                                    save.launch(ShareImagePayload.filename(payload))
                                } catch (_: ActivityNotFoundException) {
                                    toast.showMessage(getString(TextR.string.text_share_image_save_failed))
                                } catch (_: SecurityException) {
                                    toast.showMessage(getString(TextR.string.text_share_image_save_failed))
                                }
                            }
                        },
                        onShare = {
                            state.file?.let { file ->
                                try {
                                    val uri = FileProvider.getUriForFile(this, "$packageName.share-images", file)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newRawUri("", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(send, getString(TextR.string.text_share_action)))
                                } catch (_: ActivityNotFoundException) {
                                    toast.showMessage(getString(TextR.string.text_share_image_no_receiver))
                                } catch (_: IllegalArgumentException) {
                                    toast.showMessage(getString(TextR.string.text_share_image_failed))
                                } catch (_: SecurityException) {
                                    toast.showMessage(getString(TextR.string.text_share_image_no_receiver))
                                }
                            }
                        },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    companion object {
        internal fun launch(context: Context, content: ShareImageContent) {
            context.startActivity(ShareImagePayload.put(Intent(context, ShareImageActivity::class.java), content))
        }
    }
}
