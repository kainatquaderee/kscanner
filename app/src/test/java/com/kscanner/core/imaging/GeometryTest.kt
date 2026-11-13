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
}
