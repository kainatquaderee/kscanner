package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration between this frame's candidates and the outline already on screen, checked with
 * plain quads in one coordinate system: no frame, no camera, no clock.
 */
class OutlineChooserTest {

    /** A page roughly a 3:4 sheet in the middle of a preview; its span is 721 px. */
    private val page = Quad(
        Pt(100.0, 100.0),
        Pt(500.0, 100.0),
        Pt(500.0, 700.0),
        Pt(100.0, 700.0),
    )

    private fun detection(quad: Quad, confidence: Double) =
        Detection(quad, confidence, DetectMethod.EDGES)

    @Test
    fun `a candidate continuing the outline wins over a better scoring stranger`() {
        val continuing = detection(page.translated(24.0, 0.0), confidence = 0.50)
        val elsewhere = detection(page.translated(300.0, 0.0), confidence = 0.90)

        val choice = OutlineChooser.choose(listOf(elsewhere, continuing), page)

        assertEquals(OutlineChooser.Choice.Follow(continuing), choice)
    }

    @Test
    fun `a frame that only disagrees is evidence rather than a move`() {
        val elsewhere = detection(page.translated(300.0, 0.0), confidence = 0.80)

        val choice = OutlineChooser.choose(listOf(elsewhere), page)

        assertEquals(OutlineChooser.Choice.Disagree(elsewhere), choice)
    }

    @Test
    fun `a frame with nothing plausible has no news`() {
        assertEquals(
            OutlineChooser.Choice.Nothing,
            OutlineChooser.choose(listOf(detection(page, confidence = 0.29)), page),
        )
        assertEquals(OutlineChooser.Choice.Nothing, OutlineChooser.choose(emptyList(), page))
    }

    @Test
    fun `a candidate exactly at the floor is plausible`() {
        val candidate = detection(page, confidence = OutlineChooser.DEFAULT_FLOOR)

        assertEquals(
            OutlineChooser.Choice.Follow(candidate),
            OutlineChooser.choose(listOf(candidate), shown = null),
        )
    }

    @Test
    fun `with nothing on screen the best evidence starts the outline`() {
        val weak = detection(page, confidence = 0.40)
        val strong = detection(page, confidence = 0.70)

        assertEquals(
            OutlineChooser.Choice.Follow(strong),
            OutlineChooser.choose(listOf(weak, strong), shown = null),
        )
    }

    @Test
    fun `the higher confidence wins among candidates that both continue the outline`() {
        val left = detection(page.translated(10.0, 0.0), confidence = 0.55)
        val right = detection(page.translated(-10.0, 0.0), confidence = 0.65)

        assertEquals(
            OutlineChooser.Choice.Follow(right),
            OutlineChooser.choose(listOf(left, right), page),
        )
    }

    @Test
    fun `continuity is judged against the page's own size, not the frame's`() {
        // The same 60 px step is a sixteenth of the large page and most of the small one, so it is
        // the same page in the first case and a different guess in the second.
        val small = Quad(
            Pt(300.0, 300.0),
            Pt(340.0, 300.0),
            Pt(340.0, 360.0),
            Pt(300.0, 360.0),
        )
        val stepped = page.translated(60.0, 0.0)
        val smallStepped = small.translated(60.0, 0.0)

        assertTrue(
            OutlineChooser.choose(listOf(detection(stepped, 0.6)), page) is OutlineChooser.Choice.Follow,
        )
        val choice = OutlineChooser.choose(listOf(detection(smallStepped, 0.6)), small)
        assertTrue(choice is OutlineChooser.Choice.Disagree)
    }

    @Test
    fun `a page carried smoothly between detections stays continuous`() {
        // Sixty pixels every hundred milliseconds is a brisk hand movement — a twelfth of this
        // page — and every step of it has to be followed rather than treated as a new hypothesis.
        var shown = page
        repeat(12) {
            val next = shown.translated(60.0, 0.0)
            val choice = OutlineChooser.choose(listOf(detection(next, 0.6)), shown)
            assertTrue("step $it was not followed: $choice", choice is OutlineChooser.Choice.Follow)
            shown = next
        }
    }

    @Test
    fun `an outline collapsed to a line cannot say how far is far`() {
        val collapsed = Quad(Pt(100.0, 100.0), Pt(100.2, 100.0), Pt(100.2, 100.2), Pt(100.0, 100.2))
        val elsewhere = detection(page, confidence = 0.7)

        assertEquals(
            OutlineChooser.Choice.Follow(elsewhere),
            OutlineChooser.choose(listOf(elsewhere), collapsed),
        )
    }
}