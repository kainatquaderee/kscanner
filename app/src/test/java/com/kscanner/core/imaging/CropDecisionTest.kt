package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The crop decision, end to end, on the fixtures its numbers were measured on.
 *
 * Each fixture is a page drawn in page space, warped onto a canvas through a *known* quad over a
 * flat background, with bilinear resampling ([RealisticPages.photograph]). The known quad is the
 * truth — the page's share of the frame — and the delivered share is [AutoCrop.plan]'s own outline,
 * clamped to the frame and counted the same way. Comparing the two is what makes a crop that is a
 * fragment of the page visible: a small answer to a question whose right answer is large.
 *
 * What each group of tests proves:
 *
 * - **whole capture** — a sheet that runs past the view on every side has no page-shaped crop to
 *   make, so the frame is delivered whole, and the assertion is on the *size the user gets* rather
 *   than on a field, because that is what the promise is about;
 * - **repairs** — a page the first pass answered with a fragment of, because the light ran across it,
 *   is answered better by the second look, and never worse;
 * - **ceiling** — the second look is refused when it would swallow the frame, and when it is merely
 *   smaller than the first answer;
 * - **gate-trusted rows** — the rows the coverage gate trusts keep the outline they already had;
 * - **non-regressions** — the rows this decision deliberately leaves alone keep the area they had.
 */
class CropDecisionTest {

    private data class Fixture(val name: String, val image: RgbaImage, val truth: Quad)

    private fun fixture(
        name: String,
        canvasWidth: Int,
        canvasHeight: Int,
        quad: Quad,
        page: GrayImage = RealisticPages.prosePage(900, 1200),
        background: Int = 0x30,
    ) = Fixture(name, RealisticPages.photograph(page, canvasWidth, canvasHeight, quad, background), quad)

    /** Share of the frame an outline accounts for, which is the measurement every figure below is. */
    private fun area(quad: Quad?, width: Int, height: Int): Double {
        if (quad == null) return 0.0
        return quad.clampTo(width, height).area() / (width.toDouble() * height)
    }

    /**
     * A page inside the frame, turned by [degrees] about the frame's centre: the rotation of the
     * paper, not of the print, so the outline is a tilted rectangle in the frame.
     */
    private fun rotatedQuad(canvasWidth: Int, canvasHeight: Int, degrees: Double): Quad {
        val cx = canvasWidth / 2.0
        val cy = canvasHeight / 2.0
        val halfW = canvasWidth * 0.40
        val halfH = canvasHeight * 0.42
        val rad = Math.toRadians(degrees)
        fun corner(sx: Double, sy: Double): Pt {
            val x = sx * halfW
            val y = sy * halfH
            return Pt(cx + x * cos(rad) - y * sin(rad), cy + x * sin(rad) + y * cos(rad))
        }
        return Quad(corner(-1.0, -1.0), corner(1.0, -1.0), corner(1.0, 1.0), corner(-1.0, 1.0))
    }

    private val inside = Quad(Pt(100.0, 110.0), Pt(1100.0, 90.0), Pt(1110.0, 1490.0), Pt(120.0, 1510.0))
    private val bleed25 = Quad(Pt(-25.0, -30.0), Pt(1020.0, -20.0), Pt(1025.0, 1345.0), Pt(-20.0, 1340.0))
    private val bleed80 = Quad(Pt(-80.0, -90.0), Pt(1075.0, -70.0), Pt(1080.0, 1400.0), Pt(-75.0, 1400.0))

    /** A page off to one side: its right edge sits just inside, then just outside, the frame. */
    private val offToTheSide = Quad(Pt(300.0, 110.0), Pt(1290.0, 90.0), Pt(1300.0, 1490.0), Pt(320.0, 1510.0))
    private val offToTheSideOverhang = Quad(Pt(300.0, 110.0), Pt(1310.0, 90.0), Pt(1320.0, 1490.0), Pt(320.0, 1510.0))

    /** A sheet past the view top and bottom only: inside the frame on the other two sides. */
    private val tallSheet = Quad(Pt(100.0, -120.0), Pt(900.0, -120.0), Pt(900.0, 1450.0), Pt(100.0, 1450.0))

    /** A small page, well inside the frame: the shape a whole-frame answer would be wrong about. */
    private val smallPage = Quad(Pt(420.0, 520.0), Pt(800.0, 500.0), Pt(810.0, 1080.0), Pt(430.0, 1100.0))

    /**
     * The nineteen fixtures, by the name used in the tables and in the assertion messages. Each
     * entry photographs its page on demand — a 900x1200 page on a one- to two-megapixel canvas is
     * real work, and no single test needs more than six of them.
     */
    private val fixtures: Map<String, () -> Fixture> = mapOf(
        "page inside the view" to { fixture("page inside the view", 1200, 1600, inside) },
        "page inside the view (frame 1600x2133)" to {
            fixture("page inside the view (frame 1600x2133)", 1600, 2133, inside)
        },
        "page filling the view (overhang 25 px)" to {
            fixture("page filling the view (overhang 25 px)", 1000, 1333, bleed25)
        },
        "page filling the view (overhang 80 px)" to {
            fixture("page filling the view (overhang 80 px)", 1000, 1333, bleed80)
        },
        "page inside, rotated 8 degrees" to {
            fixture("page inside, rotated 8 degrees", 1200, 1600, rotatedQuad(1200, 1600, 8.0))
        },
        "page inside, uneven light, mid desk" to {
            fixture(
                "page inside, uneven light, mid desk",
                1200,
                1600,
                inside,
                RealisticPages.prosePageUnderUnevenLight(900, 1200),
                background = 0x60,
            )
        },
        "page inside, hard shadow on the lower half" to {
            fixture(
                "page inside, hard shadow on the lower half",
                1200,
                1600,
                inside,
                RealisticPages.prosePageShadowedLowHalf(900, 1200),
                background = 0x60,
            )
        },
        "page inside, dark desk" to {
            fixture("page inside, dark desk", 1200, 1600, inside, background = 0x0C)
        },
        "page inside, light desk (low contrast)" to {
            fixture("page inside, light desk (low contrast)", 1200, 1600, inside, background = 0xB8)
        },
        "page inside, very light desk" to {
            fixture("page inside, very light desk", 1200, 1600, inside, background = 0xE0)
        },
        "page inside, desk as bright as the paper" to {
            fixture("page inside, desk as bright as the paper", 1200, 1600, inside, background = 0xF0)
        },
        "sparse form inside the view" to {
            fixture(
                "sparse form inside the view",
                1200,
                1600,
                inside,
                RealisticPages.sparseTextBlock(900, 1200),
            )
        },
        "boxed table inside the view" to {
            fixture("boxed table inside the view", 1200, 1600, inside, RealisticPages.tablePage(900, 1200))
        },
        "page off to one side, partly out of frame" to {
            fixture("page off to one side, partly out of frame", 1200, 1600, offToTheSide)
        },
        "page off to one side (overhang 100 px)" to {
            fixture("page off to one side (overhang 100 px)", 1200, 1600, offToTheSideOverhang)
        },
        "tall sheet past the view top and bottom only" to {
            fixture("tall sheet past the view top and bottom only", 1000, 1333, tallSheet)
        },
        "small page far away on a dark desk" to {
            fixture("small page far away on a dark desk", 1200, 1600, smallPage, background = 0x30)
        },
        "small page far away on a light desk" to {
            fixture("small page far away on a light desk", 1200, 1600, smallPage, background = 0xB0)
        },
        "page filling the view, uneven light" to {
            fixture(
                "page filling the view, uneven light",
                1000,
                1333,
                bleed25,
                RealisticPages.prosePageUnderUnevenLight(900, 1200),
                background = 0x60,
            )
        },
    )

    private fun fixtureNamed(name: String): Fixture =
        requireNotNull(fixtures[name]) { "no fixture named $name" }.invoke()

    /** The plan the shipped call makes for one fixture, and the fixture it was made from. */
    private fun planned(name: String): Pair<Fixture, AutoCrop.Plan> {
        val fixture = fixtureNamed(name)
        return fixture to AutoCrop.plan(fixture.image, workingEdge = 1080)
    }

    /** What the plan delivers for one fixture, and the truth it is measured against. */
    private fun delivered(name: String): Pair<Double, Double> {
        val (fixture, plan) = planned(name)
        return area(plan.quad, fixture.image.width, fixture.image.height) to
            area(fixture.truth, fixture.image.width, fixture.image.height)
    }

    private fun assertDelivered(name: String, want: Double, tolerance: Double) {
        val (got, truth) = delivered(name)
        assertEquals("$name: delivered area (truth $truth)", want, got, tolerance)
    }

    /**
     * The page [ScanProcessor.process] hands back for a plan with nothing done to it: no crop (the
     * plan's own outline), no rotation, no deskew and no filter. [WHOLE_FRAME_EDGE] is above every
     * canvas here, so the size that comes back is the size that went in unless something else in the
     * pipeline resized it — which is exactly what the whole-capture promise forbids.
     */
    private fun deliveredPage(fixture: Fixture, plan: AutoCrop.Plan): RgbaImage =
        ScanProcessor.process(
            fixture.image,
            ScanProcessor.Settings(
                quad = plan.quad,
                rotationDegrees = 0,
                deskewDegrees = 0.0,
                filter = ScanFilter.ORIGINAL,
                maxOutputEdge = WHOLE_FRAME_EDGE,
            ),
        )

    private companion object {
        /** Above the longest side of every canvas here, so the whole capture is not shrunk. */
        const val WHOLE_FRAME_EDGE = 4000
    }

    // ------------------------------------------------------------------ C-2, the whole capture

    /**
     * A sheet that runs past the view on every side has no page-shaped crop to make, and the outline
     * the detector returns for it is a *fragment* of the page — 0.327 and 0.570 for two frames where
     * the page covers 0.998. Delivering that would cut the page, which is the one outcome a crop
     * must never have, so the frame goes whole instead.
     *
     * The third case is the same conclusion from the other direction: a desk as bright as the paper
     * cannot be told from it, so the page is not cut, at the cost of keeping the desk.
     *
     * The assertion is on the size the user gets, not on the field: "whole capture" is a promise
     * about the delivered picture, and a null quad that still came back cropped would satisfy a check
     * of the field alone.
     */
    @Test
    fun `a sheet that runs past the view is delivered whole`() {
        val names = listOf(
            "page filling the view (overhang 25 px)",
            "page filling the view (overhang 80 px)",
            "page inside, desk as bright as the paper",
        )

        for (name in names) {
            val (fixture, plan) = planned(name)
            assertNull("$name: no page-shaped crop to make, so there must be no outline", plan.quad)

            val delivered = deliveredPage(fixture, plan)
            assertEquals("$name: delivered width", fixture.image.width, delivered.width)
            assertEquals("$name: delivered height", fixture.image.height, delivered.height)
        }
    }

    /**
     * The same promise for a frame with no page in it at all: a uniform desk. There is no outline to
     * find, and the rim reads as paper — the whole frame is one flat level — so the frame is
     * delivered whole and never reaches the second look.
     */
    @Test
    fun `a frame with no page in it is delivered whole`() {
        val image = RgbaImage.filled(1200, 1600, Px.rgb(0x30, 0x30, 0x30))
        val plan = AutoCrop.plan(image, workingEdge = 1080)

        assertNull("a bare desk has no outline", plan.quad)
        assertNull("and nothing to report as its method", plan.method)

        val delivered = ScanProcessor.process(
            image,
            ScanProcessor.Settings(
                quad = plan.quad,
                rotationDegrees = 0,
                deskewDegrees = 0.0,
                filter = ScanFilter.ORIGINAL,
                maxOutputEdge = WHOLE_FRAME_EDGE,
            ),
        )
        assertEquals("a bare desk comes back at its own width", image.width, delivered.width)
        assertEquals("a bare desk comes back at its own height", image.height, delivered.height)
    }

    // ------------------------------------------------------------------ C-1, the repairs

    /**
     * The case the repair exists for: the light runs across the page, the first pass finds a clean
     * rectangle *inside* it, and the flat-fielded second look finds the page. Against a truth of
     * 0.726, the delivered area goes from 0.377 to 0.727 — right to a thousandth — and from 0.442 to
     * 0.496 where a hard shadow still defeats the second look part of the way.
     *
     * Both assertions are two-sided on purpose: the row must improve on the fragment it used to
     * deliver *and* land where it was measured, so a change that repaired it further would have to
     * come with a new number rather than passing quietly.
     */
    @Test
    fun `a page whose light ran across it is repaired, never cut smaller`() {
        val (repaired, repairedTruth) = delivered("page inside, uneven light, mid desk")
        assertTrue("uneven light: 0.377 was delivered before the repair, got $repaired", repaired > 0.377)
        assertEquals("uneven light, mid desk: delivered area (truth $repairedTruth)", 0.727, repaired, 0.002)

        val (shadowed, shadowedTruth) = delivered("page inside, hard shadow on the lower half")
        assertTrue("hard shadow: 0.442 was delivered before the repair, got $shadowed", shadowed > 0.442)
        assertEquals("hard shadow on the lower half: delivered area (truth $shadowedTruth)", 0.496, shadowed, 0.005)
    }

    // ------------------------------------------------------------------ the ceiling

    /**
     * The second look is a repair, not a replacement, and the guard on it is what stops a repair
     * becoming a whole-frame answer.
     *
     * On the small page on a dark desk the flat-field turns the desk into paper and the second look
     * returns 0.997 where the truth is 0.115 — an answer above `WHOLE_FRAME_CEILING`, refused. On the
     * very light desk the second look returns 0.122, *smaller* than the first answer's 0.228, so it
     * is refused as a repair too. Both rows stay where they were: 0.115 and 0.228.
     */
    @Test
    fun `a second look that would swallow the frame, or shrink it, is refused`() {
        assertDelivered("small page far away on a dark desk", want = 0.115, tolerance = 0.002)
        assertDelivered("page inside, very light desk", want = 0.228, tolerance = 0.002)
    }

    // ------------------------------------------------------------------ C-3

    /**
     * The rows the coverage gate trusts: each one's first answer already covers at least 0.60 of the
     * frame and is correct to 0.001 of its truth, so the decision must not run past step one on any
     * of them, and their outlines must be exactly what they were.
     *
     * These figures are the ones measured before this decision existed — a test cannot re-measure
     * "before" — and they are pinned here so that a change to the gate, the rim test or the ceiling
     * that reached one of these rows would fail rather than pass unnoticed. The rotated page, the page
     * on a dark desk and the page on a low-contrast desk are here for the same reason: an outline
     * that is already right is not replaced.
     */
    @Test
    fun `the rows the coverage gate trusts keep the outlines they had`() {
        val rows = mapOf(
            "page inside the view" to 0.726,
            "page inside, rotated 8 degrees" to 0.672,
            "page inside, dark desk" to 0.726,
            "page inside, light desk (low contrast)" to 0.726,
            "sparse form inside the view" to 0.726,
            "boxed table inside the view" to 0.726,
        )

        for ((name, want) in rows) assertDelivered(name, want = want, tolerance = 0.002)
    }

    /**
     * Two rows where the second look grows the first answer by 0.0002 of the frame — inside the
     * materiality floor — so it is not adopted, and the first answer is kept. The two
     * `DocDetector.detect` overloads resample differently, and on these frames the two readings
     * differ by less than the resampler's own noise rather than by any page content.
     *
     * The delivered area is the same either way, which is the point: everything downstream is derived
     * from the chosen outline, so adopting on the noise re-samples the page and can move a reading on
     * it across its own threshold without changing anything the crop is for. The tolerance is 0.005
     * rather than 0.002 here, and that is the honest precision of the comparison rather than a
     * loosened assertion: the quantity being compared is two resamplers' opinions of one frame.
     */
    @Test
    fun `the rows where the second look changes nothing measurable keep their area`() {
        assertDelivered("page inside the view (frame 1600x2133)", want = 0.409, tolerance = 0.005)
        assertDelivered("page off to one side, partly out of frame", want = 0.559, tolerance = 0.005)
    }

    // ------------------------------------------------------------------ the rows that stay put

    /**
     * The rows this decision deliberately leaves alone, each for a reason the design records.
     *
     * A page partly out of the frame: content outside the frame cannot be recovered, so the answer
     * stops at the frame edge and is short by construction. The 100 px overhang is a genuine loss at
     * 0.382 against 0.648, and the second look returns 0.205 — smaller, so refused.
     *
     * The tall sheet overhangs only top and bottom, so the rim's mean dilutes two paper sides with
     * two dark ones and reads 0.40: below the gate, and the second look at 0.450 is not larger.
     *
     * The sheet that fills the view *and* sits under a light gradient is the case the rim test cannot
     * see: its own sides read 0.27 / 0.00 / 0.00 / 0.53, a mean of 0.20, so its edges are not paper to
     * a brightness test. The repair still helps — 0.287 before — but 0.392 is nowhere near the 0.998
     * the page covers, and the design records it as still wrong.
     *
     * The small page on a light desk is here rather than with the refused second look above only
     * because it is the same page on a desk the other side of mid grey; it too stays at 0.115.
     */
    @Test
    fun `the rows that stay wrong are not made wrong differently`() {
        assertDelivered("page off to one side (overhang 100 px)", want = 0.382, tolerance = 0.005)
        assertDelivered("tall sheet past the view top and bottom only", want = 0.540, tolerance = 0.005)
        assertDelivered("page filling the view, uneven light", want = 0.392, tolerance = 0.005)
        assertDelivered("small page far away on a light desk", want = 0.115, tolerance = 0.002)
    }
}
