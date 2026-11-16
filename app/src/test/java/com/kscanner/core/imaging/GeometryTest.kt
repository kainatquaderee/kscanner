package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun `order recovers corners from a shuffled list`() {
        val truth = Quad(
            Pt(10.0, 20.0),
            Pt(310.0, 26.0),
            Pt(300.0, 430.0),
            Pt(14.0, 420.0),
        )
        val shuffled = listOf(truth.br, truth.tl, truth.bl, truth.tr)

        val ordered = Quad.order(shuffled)

        assertEquals(0.0, TestImages.maxCornerError(truth, ordered), 1e-9)
    }

    @Test
    fun `area of an axis aligned rectangle`() {
        val rect = Quad.rect(200.0, 100.0)
        assertEquals(20000.0, rect.area(), 1e-9)
    }

    @Test
    fun `convexity distinguishes a rectangle from a bow tie`() {
        assertTrue(Quad.rect(100.0, 100.0).isConvex())
        val bowTie = Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(0.0, 100.0), Pt(100.0, 100.0))
        assertFalse(bowTie.isConvex())
    }

    @Test
    fun `contains point respects an inset rectangle`() {
        val rect = Quad.rect(100.0, 100.0)
        assertTrue(rect.containsPoint(Pt(50.0, 50.0)))
        assertFalse(rect.containsPoint(Pt(150.0, 50.0)))
        assertFalse(rect.containsPoint(Pt(-1.0, 50.0)))
    }

    @Test
    fun `inset shrinks toward the centroid`() {
        val inset = Quad.rect(100.0, 100.0).insetBy(0.1)
        assertEquals(5.0, inset.tl.x, 1e-9)
        assertEquals(95.0, inset.br.x, 1e-9)
        assertEquals(8100.0, inset.area(), 1e-6)
    }

    @Test
    fun `unwarped size follows the longer opposing edges`() {
        val quad = Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 200.0), Pt(0.0, 210.0))
        val (w, h) = quad.unwarpedSize()
        assertEquals(100, w)
        assertEquals(210, h)
    }

    @Test
    fun `clamp keeps corners inside the frame`() {
        val quad = Quad(Pt(-10.0, -5.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))
        val clamped = quad.clampTo(100, 100)
        for (p in clamped.points) {
            assertTrue(p.x in 0.0..99.0)
            assertTrue(p.y in 0.0..99.0)
        }
    }

    @Test
    fun `inset frame is a valid starting outline`() {
        val frame = Quad.insetFrame(1000, 800, 0.04)
        assertTrue(frame.isConvex())
        assertEquals(960.0, frame.tr.x - frame.tl.x, 1e-6)
        assertEquals(768.0, frame.bl.y - frame.tl.y, 1e-6)
        assertTrue(frame.area() < 1000.0 * 800)
    }

    @Test
    fun `four quarter turns of the outline are the identity`() {
        val quad = sampleQuad()
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(0)), 1e-12)
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(4)), 1e-12)
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(-4)), 1e-12)
    }

    @Test
    fun `a half turn is its own inverse`() {
        val quad = sampleQuad()
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(2).rotatedQuarterTurns(2)), 1e-12)
    }

    @Test
    fun `a quarter turn and a counter quarter turn cancel`() {
        val quad = sampleQuad()
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(1).rotatedQuarterTurns(-1)), 1e-12)
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(-1).rotatedQuarterTurns(1)), 1e-12)
        assertEquals(0.0, TestImages.maxCornerError(quad, quad.rotatedQuarterTurns(1).rotatedQuarterTurns(3)), 1e-12)
        // The named counter-clockwise turn has to stay the same thing as three clockwise ones.
        assertEquals(0.0, TestImages.maxCornerError(quad.rotatedQuarterTurns(3), quad.rotated270Normalized()), 1e-12)
    }

    @Test
    fun `a clockwise quarter turn moves each corner to the next label`() {
        val quad = sampleQuad()
        val turned = quad.rotatedQuarterTurns(1)

        // (x, y) -> (1 - y, x) is the mapping of Transforms.rotate90 in normalised coordinates, and
        // a clockwise turn sends tl -> tr, so each corner takes the next label round the outline.
        fun f(p: Pt) = Pt(1.0 - p.y, p.x)
        assertEquals(0.0, turned.tr.dist(f(quad.tl)), 1e-12)
        assertEquals(0.0, turned.br.dist(f(quad.tr)), 1e-12)
        assertEquals(0.0, turned.bl.dist(f(quad.br)), 1e-12)
        assertEquals(0.0, turned.tl.dist(f(quad.bl)), 1e-12)
    }

    @Test
    fun `an outline turned with the picture stays on the same sample`() {
        // A one-sample mark in a 4 x 2 frame and a one-sample outline around it. Turning both has to
        // leave the outline on the mark the pixels took with them — the property Detect relies on
        // when it keeps the user's turn — and the frame's aspect changes, so the coordinates cannot
        // simply be swapped.
        val width = 4
        val height = 2
        val markX = 3
        val markY = 1
        val data = IntArray(width * height)
        data[markY * width + markX] = 255

        val cell = Quad(
            Pt(markX.toDouble() / width, markY.toDouble() / height),
            Pt((markX + 1).toDouble() / width, markY.toDouble() / height),
            Pt((markX + 1).toDouble() / width, (markY + 1).toDouble() / height),
            Pt(markX.toDouble() / width, (markY + 1).toDouble() / height),
        )
        val turned = cell.rotatedQuarterTurns(1)
        val rotated = Orientation.rotate90(GrayImage(width, height, data))

        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        assertTrue(turned.containsPoint(brightestCentre(rotated)))
        assertEquals(1, samplesCovered(turned, rotated))
    }

    /** A lopsided outline, so a turn that only swaps x and y cannot pass by accident. */
    private fun sampleQuad() = Quad(
        Pt(0.10, 0.20),
        Pt(0.80, 0.15),
        Pt(0.90, 0.70),
        Pt(0.05, 0.85),
    )

    private fun brightestCentre(image: GrayImage): Pt {
        var best = 0
        for (i in 1 until image.size) if (image.data[i] > image.data[best]) best = i
        return Pt(
            (best % image.width + 0.5) / image.width,
            (best / image.width + 0.5) / image.height,
        )
    }

    private fun samplesCovered(quad: Quad, image: GrayImage): Int {
        var covered = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (quad.containsPoint(Pt((x + 0.5) / image.width, (y + 0.5) / image.height))) covered++
            }
        }
        return covered
    }
}
