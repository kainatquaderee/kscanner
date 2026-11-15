package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic 180-degree decision, end to end.
 *
 * [AutoCrop.plan] is the only place the page's orientation is decided for a capture, and it is
 * decided *inside* the discovered outline on a levelled copy. These tests exercise that whole path
 * — render a synthetic photograph, run the real detector, warp and level the real outline, then
 * assert the turn the plan reports — because every part of it matters: the ink mask that turns the
 * paper/table boundary into a halo, a residual tilt that smears a text band, and a short page that
 * carries too little evidence to decide anything.
 *
 * One rule runs through all of it, and it is the reason several cases below assert "left as
 * captured" rather than a turn: **the cue may decline to decide, but it must never decide wrong.**
 * A page the pass is unsure about stays where the user put it; a page it is sure about is turned to
 * upright. Guessing, in either direction, is the failure mode these tests exist to catch.
 *
 * Nothing here is random or time-dependent, and no test depends on another's order: each case
 * renders its own fixture and reads one plan.
 */
class AutoCropOrientationTest {

    private companion object {
        const val CAPTURE_WIDTH = 1200
        const val CAPTURE_HEIGHT = 1600

        /**
         * A page photographed squarely in the middle of the frame, on a dark surface.
         *
         * Deliberately not edge to edge. `DocDetector` keeps only the nine strongest lines per
         * family, so a page with many text rows and a large area can be out-voted by its own text —
         * a separate matter, out of scope here. This geometry is the one where the detected outline
         * is the page to within a pixel or two (asserted, see [detectorCornerError]), which is what
         * lets the numbers below be a statement about orientation rather than about detection.
         */
        val PAGE_QUAD = Quad(
            Pt(300.0, 250.0),
            Pt(900.0, 250.0),
            Pt(900.0, 1350.0),
            Pt(300.0, 1350.0),
        )

        val BACKGROUND = 0xFF303030.toInt()

        /**
         * Top margin for the fixtures whose text block is small.
         *
         * [AutoCrop.flatten] insets the outline by 5%, which at 1200 px of page is 60 px, so text
         * at the default 24 px margin is trimmed away before anything is measured and the page
         * genuinely contains no text at all. A real screen of a few lines has the block lower down;
         * 140 px keeps it inside the crop.
         */
        const val SPARSE_MARGIN = 140
    }

    // ------------------------------------------------------------------ helpers

    private fun assertPlan(what: String, plan: AutoCrop.Plan, wantRotation: Int, wantDecided: Boolean) {
        assertEquals(
            "$what: wanted rotation $wantRotation, got ${plan.rotationDegrees} " +
                "(flipDecided=${plan.flipDecided}, deskew=${plan.deskewDegrees})",
            wantRotation,
            plan.rotationDegrees,
        )
        assertEquals("$what: flipDecided", wantDecided, plan.flipDecided)
    }

    /**
     * The weaker claim the sparse fixtures are entitled to: either the plan decided, in which case
     * the turn must be the one the fixture needs, or it did not, in which case the page must come
     * out exactly where the capture left it. Deciding *wrongly* is the only outcome that fails.
     */
    private fun assertDecidedCorrectlyOrUnchanged(what: String, plan: AutoCrop.Plan, needed: Int) {
        if (plan.flipDecided) {
            assertEquals("$what: decided, so the turn must be the one the fixture needs", needed, plan.rotationDegrees)
        } else {
            assertEquals("$what: undecided, so the page must be left as captured", 0, plan.rotationDegrees)
        }
    }

    /** Detector corner error in capture pixels, so a weak fixture cannot pass by accident. */
    private fun detectorCornerError(capture: RgbaImage, truth: Quad): Double {
        val preview = Transforms.fitLongestEdge(capture, 1080)
        val detection = DocDetector.detect(preview) ?: return Double.NaN
        val scale = preview.width.toDouble() / capture.width
        return TestImages.maxCornerError(truth.scaled(scale, scale), detection.quad)
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A page of prose in page space: [TestImages.asymmetricTextPage] draws each row as a trapezoid,
     * narrow at the top (ascenders and capitals) and full width at the baseline, which is exactly
     * the ink displacement the flip cue measures. Constant-thickness bars would be symmetric and
     * carry no cue at all.
     */
    private fun page(lines: Int, margin: Int = 24): RgbaImage = TestImages.asymmetricTextPage(
        width = 900,
        height = 1200,
        lines = lines,
        lineGap = 34,
        margin = margin,
    )

    private fun captureOf(page: RgbaImage): RgbaImage =
        photographed(CAPTURE_WIDTH, CAPTURE_HEIGHT, PAGE_QUAD, page)

    /**
     * A page whose *text* is turned by [tilt] degrees inside the page outline, photographed
     * squarely.
     *
     * The tilt is applied to the page before it is photographed, so the paper edge stays straight in
     * the capture while the print runs at an angle to it. That is the residual a real capture leaves
     * behind after the outline has been fitted — the detector fits the paper edge, not the print —
     * and it is the condition the orientation cue has to survive.
     */
    private fun captureOfTiltedText(lines: Int, tilt: Double): RgbaImage =
        photographed(CAPTURE_WIDTH, CAPTURE_HEIGHT, PAGE_QUAD, Transforms.rotateArbitrary(page(lines, SPARSE_MARGIN), tilt))

    /** Composes [page] onto a dark canvas through the perspective [quad]. */
    private fun photographed(canvasWidth: Int, canvasHeight: Int, quad: Quad, page: RgbaImage): RgbaImage {
        val image = RgbaImage.filled(canvasWidth, canvasHeight, BACKGROUND)
        val pageRect = Quad(
            Pt(0.0, 0.0),
            Pt((page.width - 1).toDouble(), 0.0),
            Pt((page.width - 1).toDouble(), (page.height - 1).toDouble()),
            Pt(0.0, (page.height - 1).toDouble()),
        )
        val inverse = Homography.solve(quad.points, pageRect.points)
        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                val c = Pt(x + 0.5, y + 0.5)
                if (!quad.containsPoint(c)) continue
                val p = Homography.map(inverse, c)
                if (p.x.isNaN() || p.y.isNaN()) continue
                image.set(x, y, page.sampleBilinear(p.x, p.y))
            }
        }
        return image
    }

    /** The outline after [Transforms.rotate] turns the picture by [quarter]s clockwise. */
    private fun rotatedQuarter(quad: Quad, width: Int, height: Int, quarter: Int): Quad {
        var current = quad
        var w = width
        var h = height
        repeat(quarter / 90) {
            current = Transforms.rotateQuad90(current, w, h)
            val t = w
            w = h
            h = t
        }
        return current
    }

    // ------------------------------------------------------------------ the cases

    /**
     * All four quarter turns of a realistic photographed page must be corrected, and the plan must
     * say it decided: this is the case the fix must not have broken. The turn to apply is the
     * complement of the one the capture carries — a page photographed after a 90 degree turn needs
     * another 270 to come back upright.
     *
     * The detector error is asserted too. It was measured at 1.5 px or less in every one of the four
     * turns, and if a future change made detection worse this fixture would stop being a statement
     * about orientation, so it is worth failing on rather than quietly measuring the wrong crop.
     */
    @Test
    fun `every quarter turn of a photographed page is corrected`() {
        val capture = captureOf(page(lines = 16))

        for (turn in intArrayOf(0, 90, 180, 270)) {
            val turned = Transforms.rotate(capture, turn)
            val truth = rotatedQuarter(PAGE_QUAD, CAPTURE_WIDTH, CAPTURE_HEIGHT, turn)
            val error = detectorCornerError(turned, truth)
            assertTrue(
                "quarter $turn: the outline is not the page (corner error $error px), so the " +
                    "orientation below would be measured on the wrong crop",
                error < 4.0,
            )

            assertPlan("quarter $turn", AutoCrop.plan(turned), wantRotation = (360 - turn) % 360, wantDecided = true)
        }
    }

    /**
     * The field bug the levelling step fixed, and the case the cue exists for.
     *
     * A real capture leaves a degree or two of tilt between the print and the fitted paper edge.
     * [AutoCrop] now measures the flip on a levelled copy of the page (`AutoCrop.level`), because at
     * the working scale a text band is a handful of pixels tall and one degree of tilt smears a row
     * by about its own height — enough to collapse the displacement the cue reads, and even to
     * invert its sign.
     *
     * Measured on these very fixtures, reading the cue from the un-levelled page instead — which is
     * what the plan did before the fix:
     *
     *   tilt 1.5, upright     un-levelled reads 180  -> page turned upside down
     *   tilt 1.5, turned 180  un-levelled reads   0  -> upside-down page left upside down
     *   tilt -3.0, upright    un-levelled reads 180  -> page turned upside down
     *   tilt -3.0, turned 180 un-levelled reads   0  -> upside-down page left upside down
     *
     * Each of those wrong answers came with a centroid displacement that cleared the margin, so the
     * old plan reported it as a decision rather than declining. The levelled page reads the true
     * turn in all four cases, which is what is asserted below.
     */
    @Test
    fun `a page whose text is tilted inside its outline still gets the flip right`() {
        for (tilt in doubleArrayOf(1.5, -3.0)) {
            val upright = captureOfTiltedText(lines = 16, tilt = tilt)
            assertPlan("tilt $tilt upright", AutoCrop.plan(upright), wantRotation = 0, wantDecided = true)
            assertPlan(
                "tilt $tilt turned 180",
                AutoCrop.plan(Transforms.rotate180(upright)),
                wantRotation = 180,
                wantDecided = true,
            )
        }
    }

    /**
     * A single line is one usable text band, and one band is a coin toss rather than evidence, so
     * the pass must decline and leave the page alone — whichever way up the capture is.
     *
     * This is a regression, not a hypothetical. Read from a single band, the upright fixture was
     * measured at a centroid displacement of 0.070 of band height — about half a pixel at the
     * working scale — which cleared the margin and turned an *upright* page upside down, reporting
     * it as a decision. Individual band readings across a sixteen-line page scatter by 0.02 to 0.07
     * of band height, the same size as the mean they average, so a lone band cannot be told from its
     * own noise. The turned fixture measured -0.065, again about half a pixel, and was likewise
     * "corrected" to 180 by the un-levelled path.
     *
     * The invariant worth pinning is not the answer but the refusal: nothing was decided
     * (`flipDecided` false) and the page came out where the capture left it.
     */
    @Test
    fun `a single line is left as captured`() {
        val upright = captureOf(page(lines = 1, margin = SPARSE_MARGIN))

        assertPlan("1 line upright", AutoCrop.plan(upright), wantRotation = 0, wantDecided = false)
        assertPlan(
            "1 line turned 180",
            AutoCrop.plan(Transforms.rotate180(upright)),
            wantRotation = 0,
            wantDecided = false,
        )
    }

    /**
     * A page of constant-thickness bars looks the same both ways up: the ink centroid sits in the
     * middle of every band, so there is no displacement to read, in either direction, and the two
     * candidates are mirror images of each other. Both captures must therefore be left as they are,
     * with nothing decided. Never flip on a tie.
     */
    @Test
    fun `a symmetric page is left as captured`() {
        val truth = Quad(
            Pt(70.0, 60.0),
            Pt(400.0, 42.0),
            Pt(420.0, 540.0),
            Pt(60.0, 520.0),
        )
        val upright = TestImages.documentOnBackground(480, 600, truth)

        assertPlan("bars upright", AutoCrop.plan(upright), wantRotation = 0, wantDecided = false)
        assertPlan(
            "bars turned 180",
            AutoCrop.plan(Transforms.rotate180(upright)),
            wantRotation = 0,
            wantDecided = false,
        )
    }

    /**
     * Two to four lines is thin evidence: enough for two bands to agree, not enough for a page of
     * prose's certainty. So the assertion here is not a fixed answer but the rule the cue has to
     * keep — a decision, if there is one, must be the right one, and otherwise the page stays put.
     *
     * For the record, these fixtures all currently *do* decide, and correctly: measured flip gaps
     * of 0.041 (2 lines upright), 0.052 (2 lines turned), 0.070 / 0.047 (3 lines), 0.058 / 0.047
     * (4 lines), against the wider 0.03 margin a sparse page has to clear. That is the behaviour
     * this test would notice changing, but the assertion deliberately stays with the invariant, so
     * a re-tuned margin that started declining here would not be a false failure.
     */
    @Test
    fun `sparse pages decide correctly or stay as captured`() {
        for (lines in 2..4) {
            val upright = captureOf(page(lines = lines, margin = SPARSE_MARGIN))
            assertDecidedCorrectlyOrUnchanged("$lines lines upright", AutoCrop.plan(upright), needed = 0)
            assertDecidedCorrectlyOrUnchanged(
                "$lines lines turned 180",
                AutoCrop.plan(Transforms.rotate180(upright)),
                needed = 180,
            )
        }
    }

    /**
     * "No evidence" is a distinct answer from "already upright", and the type has to keep them
     * apart: a caller offering a one-tap flip needs to know that nothing was measured, and treating
     * the fallback as a confident decision would hide exactly the pages that most need the tap.
     *
     * A blank sheet is the clearest no-evidence case there is — no ink at all — and it must come
     * back as the untouched, undecided answer.
     */
    @Test
    fun `no evidence is not the same answer as already upright`() {
        assertEquals("the fallback is not a decision", false, Orientation.UPRIGHT.flipDecided)
        assertEquals("the fallback is upright", 0, Orientation.UPRIGHT.rotationDegrees)

        val blank = GrayImage.filled(240, 320, 0xFFF0F0F0.toInt() and 0xFF)
        val estimate = Orientation.estimate(blank)
        assertEquals("a blank sheet is not a decision", false, estimate.flipDecided)
        assertEquals("a blank sheet is left upright", 0, estimate.rotationDegrees)
    }
}
