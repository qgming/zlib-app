package com.qgming.zlib.share

/** A document result may arrive before the image is recreated after process death. */
internal class ShareImageSaveQueue(
    restoredDestination: String?,
    private val persist: (String?) -> Unit,
) {
    private var destination = restoredDestination
    private var running = false

    fun enqueue(destination: String): Boolean {
        if (running || this.destination != null) return false
        this.destination = destination
        persist(destination)
        return true
    }

    fun takeWhenReady(imageReady: Boolean): String? {
        if (!imageReady || running) return null
        return destination?.also { running = true }
    }

    fun finish() {
        running = false
        destination = null
        persist(null)
    }
}
