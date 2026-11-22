package com.qgming.zlib.core.designsystem

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.staticCompositionLocalOf
import com.qgming.zlib.core.model.Book

/** A live Compose cover; only the app's Activity navigation adapter consumes snapshots. */
interface ZlibBookCoverSource {
    val radiusPx: Float
    val backgroundColor: Int
    suspend fun snapshot(): Bitmap?
    fun bounds(inWindow: Boolean): Rect?
}

data class ZlibBookOpenRequest(val book: Book, val source: ZlibBookCoverSource? = null)

/** Unsupported devices avoid recording extra graphics layers for every visible cover. */
val LocalZlibBookSnapshotsEnabled = staticCompositionLocalOf { false }
