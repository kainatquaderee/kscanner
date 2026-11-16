package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page region: [Quality.analysePage] measured over a photograph of a sheet.
 *
 * Every fixture is built the way the camera delivers a frame — [RealisticPages.photograph] resamples
 * a page onto a 320 by 440 canvas over a desk — because the whole claim of the page region is that
 * the desk, the window and the shadow *around* the paper are not the paper. The frame's own reading
 * is asserted beside the page's in most of these, because the fault this measurement exists to fix
 * is the two disagreeing: at the live analyser's scale a page held still and in focus reads BLURRY
 * as a frame, so the automatic shutter never fires.
 *
 * The numbers in [Quality]'s KDoc were read off these fixtures at 320 px, and the assertions pin
 * them. If a change moves a reading this suite should be the thing that says so.
 *
 * The desk is `0x18` unless a test says otherwise, and the page fills the frame unless it says
 * otherwise: 320 px is [Quality.WORKING_EDGE]'s case where the frame is not resized again, which is
 * the scale the live analyser actually reads ([AutoCaptureAnalyzer]'s `workingEdge`).
 */
class PageQualityTest {

    private val desk = 0x18

    /** The outline a hand-held shot of a page filling the frame lands on. */
    private fun filling(): Quad =
        Quad(Pt(22.0, 38.0), Pt(298.0, 42.0), Pt(294.0, 402.0), Pt(18.0, 398.0))

    /** A small page in the middle of the frame, with desk all round it. */
    private fun smallShot(desk: Int = this.desk): GrayImage =
        shot(RealisticPages.prosePage(), smallQuad(), desk)

    private fun smallQuad(): Quad = Quad.rect(140.0, 200.0).translated(90.0, 120.0)

    private fun shot(page: GrayImage, quad: Quad = filling(), desk: Int = this.desk): GrayImage =
        RealisticPages.photograph(page, 320, 440, quad, desk).toGray()

    /** Photographs [page] over the desk, then paints a bright band over the canvas: a window. */
    private fun window(page: GrayImage, quad: Quad, x1: Int, y1: Int): GrayImage {
        val rgba = RealisticPages.photograph(page, 320, 440, quad, desk)
        for (y in 0 until y1) {
            for (x in 0 until x1) rgba.set(x, y, Px.rgb(250, 250, 250))
        }
        return rgba.toGray()
    }

    @Test
    fun `the page region reads the page, not the desk around it`() {
        val gray = shot(RealisticPages.prosePage())
        val frame = Quality.analyse(gray)
        val page = Quality.analysePage(gray, filling())

        // The desk is most of the frame and all of the difference: the luma and the ink are the
        // paper's here and the frame's there.
        assertEquals(166.4, frame.meanLuma, 1.0)
        assertEquals(223.6, page.meanLuma, 1.0)
        assertEquals(0.2134, frame.inkFraction, 0.005)
        assertEquals(0.1173, page.inkFraction, 0.005)
        assertTrue("less ink on the page than over the desk beside it", page.inkFraction < frame.inkFraction)

        assertEquals(0.0177, page.sharpness, 0.002)

        // And the two labels disagree about the same held, in-focus page: this is the defect the
        // page region was measured to fix, and it is asserted here so it cannot come back.
        assertEquals(CaptureAdvice.BLURRY, frame.advice)
        assertEquals(CaptureAdvice.GOOD, page.advice)
    }

    @Test
    fun `a page on a desk too dark for the frame is still ready`() {
        for (surface in listOf(0x0C, 0x10, 0x12, 0x14)) {
            val gray = smallShot(surface)
            assertEquals("a desk at %02X is not a lit frame".format(surface), CaptureAdvice.TOO_DARK, Quality.analyse(gray).advice)
            val page = Quality.analysePage(gray, smallQuad())
            assertEquals("but the paper on it is lit", CaptureAdvice.GOOD, page.advice)
            assertEquals(223.7, page.meanLuma, 1.0)
        }

        // The desk does not touch the page region at all: the same paper reads the same on every one.
        val readings = listOf(0x0C, 0x10, 0x18).map { Quality.analysePage(smallShot(it), smallQuad()) }
        assertTrue("the page's own reading does not follow the desk", readings.all { it.meanLuma == readings[0].meanLuma })

        assertEquals(0.0267, Quality.analysePage(smallShot(0x10), smallQuad()).sharpness, 0.003)
    }

    @Test
    fun `a sparse form over a desk is not read as no document`() {
        val gray = shot(RealisticPages.sparseTextBlock())
        val page = Quality.analysePage(gray, filling())

        // Four lines on an otherwise blank sheet: the least ink a form can carry and still be one.
        assertTrue("four lines count as text", page.detectedText)
        assertNotEquals(CaptureAdvice.NO_DOCUMENT, page.advice)
        assertEquals(0.0176, page.inkFraction, 0.003)
        assertEquals(242.6, page.meanLuma, 1.0)
        assertEquals(CaptureAdvice.GOOD, page.advice)

        // A sheet with nothing on it is still no document: the line has not been moved out of reach.
        val blank = Quality.analysePage(shot(RealisticPages.page()), filling())
        assertEquals(CaptureAdvice.NO_DOCUMENT, blank.advice)
        assertEquals(0.0, blank.inkFraction, 1e-9)
    }

    @Test
    fun `the page boundary is not read as detail`() {
        // A uniform sheet cut out with no resampling, so the strongest edge in the frame is the
        // paper's own rim — the one thing a Laplacian taken across the outline would read as focus.
        val flat = GrayImage.filled(320, 440, desk)
        for (y in 60 until 400) {
            for (x in 40 until 300) flat.set(x, y, 246)
        }
        val outline = Quad.rect(260.0, 340.0).translated(40.0, 60.0)

        val page = Quality.analysePage(flat, outline)
        assertEquals("paper, and nothing on it", 246.0, page.meanLuma, 1e-9)
        assertEquals(0.0, page.inkFraction, 1e-9)
        assertEquals("the rim is not focus", 0.0, page.sharpness, 1e-9)

        // The frame does read that rim, so the zero above is the mask doing it and not a flat measure.
        assertTrue("the frame carries the rim's Laplacian", Quality.analyse(flat).sharpness > 0.0)
    }

    @Test
    fun `a page beside a bright window is not read as shadow`() {
        val quad = Quad.rect(160.0, 235.0).translated(148.0, 200.0)
        val gray = window(RealisticPages.prosePage(), quad, x1 = 160, y1 = 220)

        val frame = Quality.analyse(gray)
        val page = Quality.analysePage(gray, quad)

        // The window leans on two of the frame's quadrants and none of the page's.
        assertTrue("the window is over the SHADOW line", frame.illuminationRamp > 0.42)
        assertEquals(0.840, frame.illuminationRamp, 0.01)
        assertTrue("the paper is not", page.illuminationRamp < 0.42)
        assertEquals(0.063, page.illuminationRamp, 0.01)
        assertEquals(CaptureAdvice.GOOD, page.advice)

        // A real shadow across the paper still reads as one: the line has not been raised out of
        // the way of a lamp or an arm, only moved back to the light that falls on the paper.
        val shadowed = Quality.analysePage(shot(RealisticPages.prosePageShadowedLowHalf()), filling())
        assertEquals(CaptureAdvice.SHADOW, shadowed.advice)
        assertEquals(0.495, shadowed.illuminationRamp, 0.02)

        // A lamp held to one side sits under the line rather than on it, which is where it should
        // be: 0.394 against the 0.42 that says "even out the light".
        val lampToOneSide = Quality.analysePage(shot(RealisticPages.prosePageUnderUnevenLight()), filling())
        assertEquals(CaptureAdvice.GOOD, lampToOneSide.advice)
        assertTrue(lampToOneSide.illuminationRamp in 0.30..0.42)
    }

    @Test
    fun `an outline that encloses no paper is the frame's own reading`() {
        val gray = shot(RealisticPages.prosePage())
        // Wholly off the frame: nothing is masked, and the frame is the only true thing left to say.
        val offFrame = Quad.rect(50.0, 50.0).translated(400.0, 500.0)

        assertEquals(Quality.analyse(gray), Quality.analysePage(gray, offFrame))
    }

    @Test
    fun `the page's own line is what the shutter reads, not the frame's`() {
        val page = Quality.analysePage(shot(RealisticPages.prosePage()), filling())

        // One report, two lines. The page line is where analysePage runs; the frame's was fitted at
        // the working edge and is miles above anything a live frame can reach.
        assertTrue("under the frame's line", page.sharpness < Quality.SHARPNESS_BLURRY)
        assertTrue("over the page's", page.sharpness > Quality.PAGE_SHARPNESS_BLURRY)
        assertEquals(CaptureAdvice.BLURRY, Quality.advise(page))
        assertEquals(CaptureAdvice.GOOD, page.advice)

        // The line sits between the thinnest page in focus and the same page blurred by the least
        // blur there is, a single box radius.
        val blurred = Quality.analysePage(Blur.boxBlurGray(shot(RealisticPages.prosePage()), 1), filling())
        assertTrue("a blurred page is under the line", blurred.sharpness < Quality.PAGE_SHARPNESS_BLURRY)
        assertEquals(0.0007, blurred.sharpness, 2e-4)
        assertEquals(CaptureAdvice.BLURRY, blurred.advice)
    }

    @Test
    fun `every page in focus is over the line, every blurred one under it`() {
        val inFocus = listOf(
            "prose" to RealisticPages.prosePage(),
            "rules" to RealisticPages.proseWithRules(),
            "two columns" to RealisticPages.twoColumnPage(),
            "table" to RealisticPages.tablePage(),
            "stamp" to RealisticPages.proseWithStamp(),
            "signature" to RealisticPages.proseWithSignature(),
            "four lines" to RealisticPages.sparseTextBlock(),
        )
        for ((name, page) in inFocus) {
            val gray = shot(page)
            val sharp = Quality.analysePage(gray, filling()).sharpness
            assertTrue("$name in focus reads $sharp, over ${Quality.PAGE_SHARPNESS_BLURRY}", sharp > Quality.PAGE_SHARPNESS_BLURRY)
            val blurred = Quality.analysePage(Blur.boxBlurGray(gray, 1), filling()).sharpness
            assertTrue("$name blurred reads $blurred, under it", blurred < Quality.PAGE_SHARPNESS_BLURRY)
            assertTrue("and blurring lowered it", blurred < sharp)
        }

        // The thinnest of them, pinned: the margin the line has above a page it must not refuse.
        assertEquals(0.0023, Quality.analysePage(shot(RealisticPages.sparseTextBlock()), filling()).sharpness, 0.001)
    }

    @Test
    fun `the score falls as the same page is photographed finer`() {
        // Sharpness is a Laplacian variance, so it is a property of the sampling rate as much as of
        // the paper. It falls as the shot gets finer rather than rising: the pipeline's bilinear
        // passes are what the sample sees, and a larger frame is resampled from the same artwork
        // across more samples, so neighbouring pixels agree more.
        val sizes = listOf(320 to 440, 400 to 550, 465 to 640)
        val readings = sizes.map { (w, h) ->
            val quad = Quad(
                Pt(w * 0.07, h * 0.085), Pt(w * 0.93, h * 0.095),
                Pt(w * 0.92, h * 0.915), Pt(w * 0.06, h * 0.905),
            )
            Quality.analysePage(RealisticPages.photograph(RealisticPages.prosePage(), w, h, quad, desk).toGray(), quad).sharpness
        }

        assertEquals(0.0179, readings[0], 0.002)
        assertEquals(0.0117, readings[1], 0.002)
        assertEquals(0.0085, readings[2], 0.002)
        assertTrue("each finer shot reads lower than the one before", readings.zipWithNext().all { (a, b) -> a > b })
    }
}
