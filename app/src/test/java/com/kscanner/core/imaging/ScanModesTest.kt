package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanModesTest {

    // ------------------------------------------------------------------ book spread

    /** [src] repeated to fill a band of [width] and [height], so a page can be any size needed. */
    private fun tiled(src: GrayImage, width: Int, height: Int): GrayImage {
        val out = GrayImage(width, height, IntArray(width * height))
        for (y in 0 until height) {
            for (x in 0 until width) out.set(x, y, src.get(x % src.width, y % src.height))
        }
        return out
    }

    /**
     * A spread laid out the way a flattened capture of one is: two pages of real content side by
     * side, joined by a gutter of [gutter] pixels centred at [fraction] of the width.
     */
    private fun spreadOf(
        src: GrayImage,
        width: Int,
        height: Int,
        fraction: Double,
        gutter: Int,
        level: Int,
    ): GrayImage {
        val centre = (width * fraction).toInt()
        val leftWidth = centre - gutter / 2
        val left = tiled(src, leftWidth, height)
        val right = tiled(src, width - centre - gutter / 2, height)
        val out = GrayImage(width, height, IntArray(width * height))
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = when {
                    x < leftWidth -> left.get(x, y)
                    x < centre + gutter / 2 -> level
                    else -> right.get(x - centre - gutter / 2, y)
                }
                out.set(x, y, v)
            }
        }
        return out
    }

    /**
     * A narrow column of dense print down the middle of an otherwise ordinary page: a single page
     * whose centre column is dark, which is the case the replaced single-column test accepted.
     */
    private fun narrowPrintColumn(src: GrayImage, columnWidth: Int): GrayImage {
        val out = src.copy()
        val scale = 2
        val pitch = RealisticPages.cellHeight(scale) + 2 * scale
        val chars = (columnWidth - 8) / RealisticPages.advance(scale)
        RealisticPages.drawParagraph(
            out,
            chars,
            src.height / pitch - 2,
            (src.width - columnWidth) / 2,
            40,
            scale,
            RealisticPages.INK,
            pitch,
        )
        return out
    }

    @Test
    fun `splitting a spread keeps the outer edges and covers the whole area`() {
        val quad = Quad(
            Pt(40.0, 30.0),
            Pt(440.0, 44.0),
            Pt(452.0, 590.0),
            Pt(30.0, 576.0),
        )

        val split = Spread.split(quad, 0.5)

        assertEquals("left keeps the original top-left", quad.tl.x, split.left.tl.x, 1e-9)
        assertEquals("right keeps the original top-right", quad.tr.x, split.right.tr.x, 1e-9)
        assertEquals("left keeps the original bottom-left", quad.bl.y, split.left.bl.y, 1e-9)

        val combined = split.left.area() + split.right.area()
        assertEquals("halves tile the spread", quad.area(), combined, quad.area() * 0.02)
        assertTrue("both halves are convex", split.left.isConvex() && split.right.isConvex())
    }

    @Test
    fun `a creased sheet reads as a spread, and the seam is off centre`() {
        val image = TestImages.spreadWithGutter(width = 400, height = 300, seamFraction = 0.62)
            .toGray()

        val verdict = Spread.decide(image)

        assertTrue("a creased sheet reads as a spread", verdict.isSpread)
        assertTrue("seam was ${verdict.seam}", Math.abs(verdict.seam - 0.62) < 0.06)
        assertEquals("the seam is the one findSeam found", Spread.findSeam(image), verdict.seam, 1e-9)
    }

    @Test
    fun `flat paper is not mistaken for a spread`() {
        val flat = TestImages.solid(400, 300, 0xFFF0F0F0.toInt()).toGray()

        assertTrue("no gutter, no spread", !Spread.decide(flat).isSpread)
    }

    @Test
    fun `a shot too small to measure is one page, not a guess`() {
        val verdict = Spread.decide(TestImages.solid(20, 20, 0xFFF0F0F0.toInt()).toGray())

        assertTrue("nothing was measured", !verdict.isSpread)
        assertEquals("and the middle is left alone", 0.5, verdict.seam, 1e-9)
    }

    @Test
    fun `a page of print is one page, however dark its columns are`() {
        val print = TestImages.textPage().toGray()
        val prose = RealisticPages.prosePage()

        assertTrue("a page of lines of print", !Spread.decide(print).isSpread)
        assertTrue("a page of prose", !Spread.decide(prose).isSpread)
    }

    @Test
    fun `a narrow column of print down the middle is one page`() {
        // Narrow enough to pass the width and depth tests, which is why the full-height test
        // exists: print leaves paper between its lines, and a crease does not.
        val page = narrowPrintColumn(RealisticPages.page(1800, 1200), 144)

        val verdict = Spread.decide(page)

        assertTrue("a text column is not a gutter", !verdict.isSpread)
    }

    @Test
    fun `a page with ink across its middle is one page`() {
        val block = RealisticPages.solidBlockPage()

        assertTrue("a solid block is not a crease", !Spread.decide(block).isSpread)
    }

    @Test
    fun `a shadow over part of a page is one page`() {
        val uneven = RealisticPages.prosePageUnderUnevenLight()
        val lowHalf = RealisticPages.prosePageShadowedLowHalf()

        assertTrue("uneven light", !Spread.decide(uneven).isSpread)
        assertTrue("a shadow across the lower half", !Spread.decide(lowHalf).isSpread)
    }

    @Test
    fun `a brightly lit gutter is still a gutter`() {
        // Twelve grey levels deep: what a crease looks like when the lamp is on it, and shallower
        // than the sixteen the replaced test demanded.
        val image = TestImages.spreadWithGutter(width = 400, height = 300, crease = 0xFFE6E6E6.toInt())
            .toGray()

        assertTrue("a shallow crease is still a crease", Spread.decide(image).isSpread)
    }

    @Test
    fun `a spread is decided at both ends of the range a gutter can sit in`() {
        for (fraction in listOf(0.30, 0.70)) {
            val image = spreadOf(RealisticPages.prosePage(), 1800, 1200, fraction, gutter = 40, level = 0x60)

            val verdict = Spread.decide(image)

            assertTrue("gutter at $fraction decided $verdict", verdict.isSpread)
            assertTrue(
                "cut at ${verdict.seam}, gutter at $fraction",
                Math.abs(verdict.seam - fraction) <= 0.05,
            )
            assertTrue("seam left the search range", verdict.seam >= 0.30 && verdict.seam <= 0.70)
        }
    }

    // ------------------------------------------------------------------ id card

    @Test
    fun `stacking two captures keeps both and separates them`() {
        val front = TestImages.solid(300, 190, 0xFFAA2222.toInt())
        val back = TestImages.solid(300, 190, 0xFF2222AA.toInt())

        val page = Composite.stacked(front, back)

        assertEquals("common width", 300, page.width)
        assertTrue("taller than one card", page.height > 190 * 2)

        // The first and last rows are margin; the cards sit inside them.
        val topCard = page.get(150, page.height / 4)
        val bottomCard = page.get(150, page.height - page.height / 4)
        assertEquals(0xFFAA2222.toInt(), topCard)
        assertEquals(0xFF2222AA.toInt(), bottomCard)
        assertEquals("sheet is white", 0xFFFFFFFF.toInt(), page.get(150, 2))
    }

    // ------------------------------------------------------------------ auto crop

    @Test
    fun `auto crop finds the page and aligns it with the truth`() {
        val truth = Quad(
            Pt(70.0, 60.0),
            Pt(400.0, 42.0),
            Pt(420.0, 540.0),
            Pt(60.0, 520.0),
        )
        val photo = TestImages.documentOnBackground(480, 600, truth)

        val plan = AutoCrop.plan(photo)

        assertNotNull("an outline was expected", plan.quad)
        assertEquals("already upright", 0, plan.rotationDegrees)
        assertEquals("outline comes from the edges", DetectMethod.EDGES, plan.method)
        val error = TestImages.maxCornerError(truth, plan.quad!!)
        assertTrue("corner error was $error px", error < 14.0)
        assertTrue("confidence was ${plan.confidence}", plan.confidence > 0.4)
    }

    @Test
    fun `auto crop respects an aspect hint`() {
        // A card-shaped page: 1.586 is the ID-1 ratio.
        val card = Quad(
            Pt(120.0, 260.0),
            Pt(440.0, 260.0),
            Pt(440.0, 462.0),
            Pt(120.0, 462.0),
        )
        val photo = TestImages.documentOnBackground(560, 720, card)

        val plan = AutoCrop.plan(photo, aspectHint = 1.586)

        assertNotNull(plan.quad)
        val e = plan.quad!!.edgeLengths()
        val ratio = (e[0] + e[2]) / 2.0 / ((e[1] + e[3]) / 2.0)
        assertTrue("ratio was $ratio", Math.abs(ratio - 1.586) / 1.586 < 0.25)
    }

    /**
     * The plan carries the outline twice, in pixels and as 0..1 fractions, because the two
     * differ by the frame size. Mixing them up inflated the outline by the downscale factor
     * and dragged it off the picture. The capture here is deliberately bigger than the
     * detector's working edge, so the two spaces genuinely differ.
     */
    @Test
    fun `the normalised outline is the pixel outline over the frame size`() {
        val truth = Quad(
            Pt(300.0, 250.0),
            Pt(1330.0, 190.0),
            Pt(1420.0, 1810.0),
            Pt(270.0, 1740.0),
        )
        val photo = TestImages.documentOnBackground(1600, 2000, truth)

        val plan = AutoCrop.plan(photo)

        val quad = plan.quad
        assertNotNull("an outline was expected", quad)
        val normalised = plan.quadNormalised
        assertNotNull("the normalised outline travels with the pixel one", normalised)

        // What the editor draws: every corner has to stay on the picture.
        normalised!!.points.forEach {
            assertTrue("corner $it left the frame", it.x >= -0.05 && it.x <= 1.05 && it.y >= -0.05 && it.y <= 1.05)
        }

        // And it is the pixel outline divided by the rotated frame, not by a preview size.
        val frameWidth = if (plan.rotationDegrees % 180 == 0) photo.width else photo.height
        val frameHeight = if (plan.rotationDegrees % 180 == 0) photo.height else photo.width
        val restored = normalised.scaled(frameWidth.toDouble(), frameHeight.toDouble())
        val error = TestImages.maxCornerError(quad!!, restored)
        assertTrue("normalised and pixel outlines disagree by $error px", error < 2.0)
    }
}
