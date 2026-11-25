package com.qgming.zlib.share

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareImageFilesTest {
    @Test
    fun `a failed or empty PNG never remains shareable`() = withCache { directory ->
        val files = ShareImageFiles(directory)
        assertFailsWith<IOException> { files.write { target -> target.writeBytes(byteArrayOf()) } }
        assertFailsWith<IOException> {
            files.write { target ->
                target.writeBytes(byteArrayOf(1, 2))
                throw IOException("Synthetic encoding failure")
            }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cache files are unique and stale generated images are pruned without other files`() = withCache { directory ->
        val files = ShareImageFiles(directory)
        val previous = files.write { target -> target.writeBytes(byteArrayOf(1)) }
        val now = System.currentTimeMillis()
        assertTrue(previous.setLastModified(now - 25L * 60 * 60 * 1_000))
        val unrelated = File(directory, "keep.txt").apply { writeText("Unrelated file") }
        assertTrue(unrelated.setLastModified(previous.lastModified()))
        val first = files.write(now) { target -> target.writeBytes(byteArrayOf(2)) }
        val second = files.write(now) { target -> target.writeBytes(byteArrayOf(3)) }
        assertFalse(previous.exists())
        assertTrue(unrelated.exists())
        assertTrue(first != second)
        assertEquals(listOf<Byte>(2), first.readBytes().toList())
        assertEquals(listOf<Byte>(3), second.readBytes().toList())
        assertTrue(files.contains(first))
        assertTrue(files.contains(second))
        assertFalse(files.contains(unrelated))
    }

    @Test
    fun `only nonempty generated images in the exact cache directory are shareable`() = withCache { directory ->
        val files = ShareImageFiles(directory)
        val outside = File(directory.parentFile, "00000000-0000-0000-0000-000000000000.png").apply { writeText("outside") }
        try {
            assertFalse(files.contains(outside))
            val invalidName = File(directory, "image.png").apply { writeText("inside") }
            assertFalse(files.contains(invalidName))
            val empty = File(directory, "00000000-0000-0000-0000-000000000000.png").apply { writeBytes(byteArrayOf()) }
            assertFalse(files.contains(empty))
        } finally {
            outside.delete()
        }
    }

    private fun withCache(block: (File) -> Unit) {
        val root = Files.createTempDirectory("zlib-share-test-").toFile()
        try {
            block(File(root, "share-images").apply { mkdirs() })
        } finally {
            root.deleteRecursively()
        }
    }
}
