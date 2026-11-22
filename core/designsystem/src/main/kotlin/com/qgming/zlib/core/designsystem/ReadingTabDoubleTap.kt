package com.qgming.zlib.core.designsystem

/** Selection callbacks also fire for the already selected destination. */
internal class ReadingTabDoubleTap(private val timeoutMillis: Long) {
    private var lastReadingTap: Long? = null

    fun register(reading: Boolean, timestampMillis: Long): Boolean {
        if (!reading) {
            lastReadingTap = null
            return false
        }
        val previous = lastReadingTap
        if (previous != null && timestampMillis - previous in 0..timeoutMillis) {
            lastReadingTap = null
            return true
        }
        lastReadingTap = timestampMillis
        return false
    }
}
