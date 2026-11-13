package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanModesTest {

    // ------------------------------------------------------------------ book spread

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
    fun `the gutter is found off centre`() {
        val image = TestImages.spreadWithGutter(width = 400, height = 300, seamFraction = 0.62)
            .toGray()

        val seam = Spread.findSeam(image)

        assertTrue("seam was $seam", Math.abs(seam - 0.62) < 0.06)
        assertTrue("a creased sheet reads as a spread", Spread.looksLikeSpread(image))
    }

    @Test
    fun `flat paper is not mistaken for a spread`() {
        val flat = TestImages.solid(400, 300, 0xFFF0F0F0.toInt()).toGray()

        assertTrue("no gutter, no spread", !Spread.looksLikeSpread(flat))
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
