package com.qgming.zlib.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareImageSaveQueueTest {
    @Test
    fun `document result arriving before content initialization waits for the rendered image`() {
        var persisted: String? = null
        val queue = ShareImageSaveQueue(null) { persisted = it }
        assertTrue(queue.enqueue(DESTINATION))
        assertEquals(DESTINATION, persisted)
        assertNull(queue.takeWhenReady(imageReady = false))
        assertEquals(DESTINATION, queue.takeWhenReady(imageReady = true))
        assertNull(queue.takeWhenReady(imageReady = true))
        queue.finish()
        assertNull(persisted)
        assertNull(queue.takeWhenReady(imageReady = true))
    }

    @Test
    fun `process recreation restores a queued destination and waits for image regeneration`() {
        var persisted: String? = null
        val original = ShareImageSaveQueue(null) { persisted = it }
        original.enqueue(DESTINATION)
        original.takeWhenReady(imageReady = true)
        val recreated = ShareImageSaveQueue(persisted) { persisted = it }
        assertNull(recreated.takeWhenReady(imageReady = false))
        assertEquals(DESTINATION, recreated.takeWhenReady(imageReady = true))
        recreated.finish()
        assertNull(persisted)
    }

    @Test
    fun `a pending or active write cannot be replaced and a finished write accepts the next request`() {
        val queue = ShareImageSaveQueue(null) { }
        assertTrue(queue.enqueue(DESTINATION))
        assertFalse(queue.enqueue("content://documents/other"))
        assertEquals(DESTINATION, queue.takeWhenReady(imageReady = true))
        assertFalse(queue.enqueue("content://documents/other"))
        queue.finish()
        assertTrue(queue.enqueue("content://documents/other"))
        assertEquals("content://documents/other", queue.takeWhenReady(imageReady = true))
    }

    private companion object {
        const val DESTINATION = "content://documents/chosen-image"
    }
}
