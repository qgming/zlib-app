package com.qgming.zlib.core.designsystem

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.model.EbookFiles
import com.qgming.zlib.core.model.LocalFile
import java.io.FileNotFoundException

/** Opens an already validated SAF document with the same temporary read grant in every feature. */
fun launchLocalFile(context: Context, file: LocalFile, share: Boolean = false): String? = try {
    val uri = file.uri.toUri()
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) {
            type = EbookFiles.mimeType(file.extension)
            putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            setDataAndType(uri, EbookFiles.mimeType(file.extension))
        }
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val launch = if (share) Intent.createChooser(intent, appText(TextR.string.text_share, file.title)) else intent
    context.startActivity(launch)
    null
} catch (_: ActivityNotFoundException) {
    appText(if (share) TextR.string.text_no_app_can_receive_this_file else TextR.string.text_no_app_can_open_this_format_please_install_a_reader)
} catch (_: SecurityException) {
    appText(TextR.string.text_cannot_access_the_file_grant_folder_access_again_in_settings)
} catch (_: FileNotFoundException) {
    appText(TextR.string.text_local_file_not_found_may_have_been_deleted)
}
