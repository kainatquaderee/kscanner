package com.kscanner.core.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocDetectorTest {

    @Test
    fun `detects a perspective distorted page`() {
        val truth = Quad(
            Pt(70.0, 60.0),
            Pt(400.0, 42.0),
            Pt(420.0, 540.0),
            Pt(60.0, 520.0),
        )
        val photo = TestImages.documentOnBackground(480, 600, truth)

        val result = DocDetector.detect(photo)

        assertNotNull("expected an outline to be found", result)
        val error = TestImages.maxCornerError(truth, result!!.quad)
        assertTrue("corner error was $error px", error < 12.0)
    }

    @Test
    fun `detects an axis aligned page`() {
        val truth = Quad(
            Pt(60.0, 50.0),
            Pt(400.0, 50.0),
            Pt(400.0, 540.0),
            Pt(60.0, 540.0),
        )
        val photo = TestImages.documentOnBackground(480, 600, truth)

        val result = DocDetector.detect(photo)

        assertNotNull(result)
        val error = TestImages.maxCornerError(truth, result!!.quad)
        assertTrue("corner error was $error px", error < 12.0)
    }

    @Test
    fun `prefers the page boundary over dense interior rules`() {
        // A form-like page: many long horizontal rules that individually out-score nothing,
        // but together make attractive interior quads.
        val truth = Quad(
            Pt(50.0, 40.0),
            Pt(430.0, 40.0),
            Pt(430.0, 560.0),
            Pt(50.0, 560.0),
        )
        val photo = TestImages.documentOnBackground(
            canvasWidth = 480,
            canvasHeight = 600,
            pageQuad = truth,
            textLines = 14,
            margin = 14.0,
            barHeight = 5.0,
            lineGap = 30.0,
        )

        val result = DocDetector.detect(photo)

        assertNotNull(result)
        val error = TestImages.maxCornerError(truth, result!!.quad)
        assertTrue("corner error was $error px", error < 16.0)
    }

    @Test
    fun `returns null when the frame has no page in it`() {
        val flat = TestImages.solid(400, 400, 0xFF808080.toInt())
        assertNull(DocDetector.detect(flat))
    }

    @Test
    fun `rejects an image that is too small to analyse`() {
        assertNull(DocDetector.detect(TestImages.solid(8, 8, 0)))
    }

    @Test
    fun `reported outline is always convex and inside the frame`() {
        val truth = Quad(
            Pt(70.0, 60.0),
            Pt(400.0, 42.0),
            Pt(420.0, 540.0),
            Pt(60.0, 520.0),
        )
        val photo = TestImages.documentOnBackground(480, 600, truth)

        val result = DocDetector.detect(photo)!!

        assertTrue(result.quad.isConvex())
        assertTrue(result.confidence in 0.0..1.0)
        for (p in result.quad.points) {
            assertTrue(p.x >= -1.0 && p.x <= photo.width + 1.0)
            assertTrue(p.y >= -1.0 && p.y <= photo.height + 1.0)
        }
    }
}
