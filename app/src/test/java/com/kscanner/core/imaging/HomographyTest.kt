package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyTest {

    @Test
    fun `identity mapping reproduces the source points`() {
        val points = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val h = Homography.solve(points, points)
        for (p in points) {
            val mapped = Homography.map(h, p)
            assertEquals(p.x, mapped.x, 1e-9)
            assertEquals(p.y, mapped.y, 1e-9)
        }
    }

    @Test
    fun `solved transform reproduces every correspondence`() {
        val from = listOf(Pt(0.0, 0.0), Pt(300.0, 0.0), Pt(300.0, 420.0), Pt(0.0, 420.0))
        val to = listOf(Pt(70.0, 61.0), Pt(402.0, 44.0), Pt(419.0, 538.0), Pt(62.0, 521.0))

        val h = Homography.solve(from, to)

        for (i in from.indices) {
            val mapped = Homography.map(h, from[i])
            assertEquals("corner $i x", to[i].x, mapped.x, 1e-6)
            assertEquals("corner $i y", to[i].y, mapped.y, 1e-6)
        }
    }

    @Test
    fun `warping a quad onto itself is effectively the identity`() {
        val source = TestImages.solid(120, 90, 0xFF3366CC.toInt())
        source.set(0, 0, 0xFFFF0000.toInt())
        source.set(119, 89, 0xFF00FF00.toInt())

        val quad = Quad.rect(120.0, 90.0)
        val warped = Homography.warpPerspective(source, quad, 120, 90)

        assertEquals(120, warped.width)
        assertEquals(90, warped.height)
        assertEquals(0xFFFF0000.toInt(), warped.get(0, 0))
        assertEquals(0xFF00FF00.toInt(), warped.get(119, 89))
    }

    @Test
    fun `warping with the true outline flattens the page edge to edge`() {
        val truth = Quad(
            Pt(70.0, 60.0),
            Pt(400.0, 42.0),
            Pt(420.0, 540.0),
            Pt(60.0, 520.0),
        )
        val photo = TestImages.documentOnBackground(480, 600, truth)

        val flattened = Homography.warpPerspective(photo, truth)

        // The outline's aspect ratio is preserved within a few percent.
        val expectedAspect = TestImages.PAGE_WIDTH / TestImages.PAGE_HEIGHT
        val actualAspect = flattened.width.toDouble() / flattened.height
        assertEquals(expectedAspect, actualAspect, 0.08)

        // The extreme perimeter blends paper into background, because the outline sits
        // exactly on that transition. Verify a ring a few pixels inside instead: had the
        // crop leaked the surroundings, dark background pixels would show up here.
        val inset = 6
        var darkSamples = 0
        var samples = 0
        fun check(x: Int, y: Int) {
            samples++
            val c = flattened.get(x, y)
            if (Px.luma(Px.r(c), Px.g(c), Px.b(c)) < 180) darkSamples++
        }
        for (x in inset until flattened.width - inset) {
            check(x, inset)
            check(x, flattened.height - 1 - inset)
        }
        for (y in inset until flattened.height - inset) {
            check(inset, y)
            check(flattened.width - 1 - inset, y)
        }
        val darkRatio = darkSamples.toDouble() / samples
        assertTrue("ring inside the crop should be paper, dark ratio was $darkRatio", darkRatio < 0.02)
    }

    @Test
    fun `output size is capped while preserving aspect ratio`() {
        val quad = Quad.rect(4000.0, 3000.0)
        val (w, h) = Homography.outputSizeFor(quad, 1000)
        assertEquals(1000, w)
        assertEquals(750, h)
    }
}
