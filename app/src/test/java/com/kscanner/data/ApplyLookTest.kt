package com.kscanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which pages "Apply to all" is offered on, which pages it touches, and what it says before and
 * after.
 *
 * The counts are the whole of the evidence a run leaves: the editor shows no per-page list, and a
 * cancelled run leaves some pages changed and some not. A summary that rounded them, or that
 * described a stop as a failure, would tell someone their document is in a state it is not in.
 */
class ApplyLookTest {

    @Test
    fun `a capture that has not been saved is not offered the action`() {
        assertFalse(ApplyLook.isAvailable(pageCount = 4, editingPageId = -1L))
    }

    @Test
    fun `a document of one page is not offered the action`() {
        assertFalse(ApplyLook.isAvailable(pageCount = 1, editingPageId = 7L))
    }

    @Test
    fun `a saved page with another to copy onto is offered the action`() {
        assertTrue(ApplyLook.isAvailable(pageCount = 2, editingPageId = 7L))
        assertTrue(ApplyLook.isAvailable(pageCount = 11, editingPageId = 0L))
    }

    @Test
    fun `every other page is a target and the page being edited never is`() {
        val pages = listOf(page(1L, 0), page(2L, 1), page(3L, 2), page(4L, 3))

        val targets = ApplyLook.targets(pages, editingPageId = 3L)

        assertEquals(listOf(1L, 2L, 4L), targets.map { it.id })
        assertFalse(targets.any { it.id == 3L })
    }

    @Test
    fun `the confirmation names the number of pages it would change`() {
        assertEquals("Apply this look to the other 10 pages?", ApplyLook.confirmation(10))
        assertEquals("Apply this look to the other 1 page?", ApplyLook.confirmation(1))
    }

    @Test
    fun `progress counts against the document, not the run`() {
        assertEquals("Applying page 3 of 11...", ApplyLook.progress(3, 11))
        assertEquals("Applying page 1 of 2...", ApplyLook.progress(1, 2))
    }

    @Test
    fun `a completed run reports the pages it changed`() {
        assertEquals(
            "The look was applied to 10 pages.",
            ApplyLook.summary(applied = 10, failed = 0, cancelled = false, total = 10),
        )
    }

    @Test
    fun `a page that could not be updated is counted apart from the ones that were`() {
        assertEquals(
            "The look was applied to 9 pages. 1 page could not be updated.",
            ApplyLook.summary(applied = 9, failed = 1, cancelled = false, total = 10),
        )
    }

    @Test
    fun `a cancelled run says how far it got and is not called a failure`() {
        val summary = ApplyLook.summary(applied = 5, failed = 0, cancelled = true, total = 10)

        assertEquals("Stopped after 5 of 10 pages. The look was applied to 5 pages.", summary)
        assertFalse(summary.contains("could not"))
    }

    @Test
    fun `a run stopped after a failure keeps both counts`() {
        assertEquals(
            "Stopped after 5 of 10 pages. The look was applied to 4 pages. 1 page could not be updated.",
            ApplyLook.summary(applied = 4, failed = 1, cancelled = true, total = 10),
        )
    }

    @Test
    fun `cancelling before the first page claims no change at all`() {
        assertEquals(
            "Cancelled. No pages were changed.",
            ApplyLook.summary(applied = 0, failed = 0, cancelled = true, total = 10),
        )
    }

    @Test
    fun `a run that changed nothing still says so`() {
        assertEquals(
            "No pages were changed.",
            ApplyLook.summary(applied = 0, failed = 0, cancelled = false, total = 10),
        )
    }

    private fun page(id: Long, index: Int) = Page(
        id = id,
        documentId = 42L,
        index = index,
        originalPath = "/tmp/original-$id.jpg",
        processedPath = "/tmp/page-$id.jpg",
        filterId = 0,
        rotation = 0,
        corners = null,
        ocrText = null,
        ocrLines = null,
        width = 100,
        height = 140,
        createdAt = 0L,
    )
}