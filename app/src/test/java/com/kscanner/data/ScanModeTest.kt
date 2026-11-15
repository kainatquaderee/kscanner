package com.kscanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored-mode migration, which is the one part of the Batch toggle that has to keep working
 * for a library captured before it existed.
 *
 * Batch was a mode of its own named `BATCH` up to 1.4.0, and Book and ID card were always
 * continuous. A decode that got any of these wrong would silently change what the camera does
 * for someone who never opened the settings: either a rapid-capture habit collapsed into one
 * shot to the editor, or a single-page habit became an ever-growing document.
 */
class ScanModeTest {

    @Test
    fun `legacy batch decodes to one page per shot with batch on`() {
        val (mode, batch) = ScanMode.decodeStored("BATCH")

        assertEquals(ScanMode.SINGLE, mode)
        assertTrue(batch)
    }

    @Test
    fun `the shapes that were always continuous keep batch on`() {
        assertEquals(ScanMode.BOOK to true, ScanMode.decodeStored("BOOK"))
        assertEquals(ScanMode.ID_CARD to true, ScanMode.decodeStored("ID_CARD"))
    }

    @Test
    fun `a stored single stays a one-shot run`() {
        assertEquals(ScanMode.SINGLE to false, ScanMode.decodeStored("SINGLE"))
    }

    @Test
    fun `nothing stored, or something unreadable, defaults to the one-shot run`() {
        assertEquals(ScanMode.SINGLE to false, ScanMode.decodeStored(null))
        assertEquals(ScanMode.SINGLE to false, ScanMode.decodeStored(""))
        assertEquals(ScanMode.SINGLE to false, ScanMode.decodeStored("SOMETHING_ELSE"))
    }

    @Test
    fun `every current mode decodes back to itself`() {
        for (mode in ScanMode.entries) {
            assertEquals(mode, ScanMode.decodeStored(mode.name).first)
        }
    }

    @Test
    fun `only the one-page shape can be re-shot one page at a time`() {
        val onePerShot = ScanMode.entries.filter { it.isOnePagePerShot }

        assertEquals(listOf(ScanMode.SINGLE), onePerShot)
    }

    @Test
    fun `only the card steers detection with an aspect ratio`() {
        assertEquals(1.586, ScanMode.ID_CARD.aspectHint)
        assertNull(ScanMode.SINGLE.aspectHint)
        assertNull(ScanMode.BOOK.aspectHint)
    }

    @Test
    fun `each mode has a label and a hint to show`() {
        for (mode in ScanMode.entries) {
            assertTrue(mode.label.isNotBlank())
            assertTrue(mode.hint.isNotBlank())
        }
    }
}
