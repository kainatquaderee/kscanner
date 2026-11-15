package com.kscanner.core.io

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The decode key is the one part of the "a rotation must repaint" fix that a plain JVM can
 * decide: `BitmapFactory` returns null under `isReturnDefaultValues`, so nothing above
 * `imageStateKey` is testable here — but the key is pure `java.io`.
 */
class ImageStateTest {

    private val scratch = ArrayList<File>()

    private fun tempFile(): File =
        File.createTempFile("image-state", ".jpg").also {
            scratch.add(it)
            it.deleteOnExit()
        }

    @After
    fun cleanUp() {
        scratch.forEach { runCatching { it.delete() } }
    }

    @Test
    fun `a file that is not rewritten keeps its key`() {
        val file = tempFile()
        file.writeText("same bytes")

        assertEquals(imageStateKey(file.absolutePath), imageStateKey(file.absolutePath))
    }

    @Test
    fun `a rewrite of the same path changes the key`() {
        val file = tempFile()
        file.writeText("one")
        val before = imageStateKey(file.absolutePath)

        file.writeText("two, and longer")

        assertNotEquals(before, imageStateKey(file.absolutePath))
    }

    @Test
    fun `a same-length rewrite at a new timestamp changes the key`() {
        val file = tempFile()
        file.writeText("aaaa")
        file.setLastModified(1_000_000_000_000L)
        val before = imageStateKey(file.absolutePath)

        file.writeText("bbbb")
        file.setLastModified(1_000_000_000_500L)

        assertNotEquals(before, imageStateKey(file.absolutePath))
    }

    /**
     * Renamed from "a missing path has no key", which contradicted the sibling test below: a path
     * with no *file* behind it deliberately still keys, so "missing" was doing two jobs in two
     * names. What this one pins is the null/blank case, where there is no path to decode at all.
     */
    @Test
    fun `a null or blank path has no key`() {
        assertNull(imageStateKey(null))
        assertNull(imageStateKey(""))
        assertNull(imageStateKey("   "))
    }

    @Test
    fun `two files with equal length and mtime do not collide`() {
        val first = tempFile()
        val second = tempFile()
        first.writeText("same size")
        second.writeText("same size")
        val stamp = 1_500_000_000_000L
        first.setLastModified(stamp)
        second.setLastModified(stamp)

        assertEquals(first.length(), second.length())
        assertEquals(first.lastModified(), second.lastModified())

        assertNotEquals(imageStateKey(first.absolutePath), imageStateKey(second.absolutePath))
    }

    /**
     * A path with no file behind it is not "nothing to decode". The decoder will be handed that
     * path and fail, so the key still has to be a stable identity for it — and it has to be
     * distinct from the identity of a different missing path, or two absent files would share a
     * cache entry. Null is reserved for "there is no path at all", which is what a caller tests
     * for before it attempts a decode.
     *
     * The second half is the property that makes the key useful rather than merely non-null: when
     * the file appears, the key must move, so a decode that failed on a missing file is not served
     * from a cache entry once the file exists.
     */
    @Test
    fun `a path with no file behind it keys, and rekeys once the file exists`() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "image-state-absent-${System.nanoTime()}.jpg",
        )
        scratch.add(missing)
        assertFalse("the fixture must not exist for this test to mean anything", missing.exists())

        val absent = imageStateKey(missing.absolutePath)
        assertNotNull("a missing file is still an identity, not a blank path", absent)
        assertNotEquals(
            "two absent files must not share a cache entry",
            absent,
            imageStateKey("${missing.absolutePath}.other"),
        )

        missing.writeText("here now")

        assertNotEquals(
            "the key must move once the file exists",
            absent,
            imageStateKey(missing.absolutePath),
        )
    }
}