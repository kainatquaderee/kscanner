package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AnalysisTest {

    // ------------------------------------------------------------------ orientation

    @Test
    fun `an upright page is left alone`() {
        val page = TestImages.asymmetricTextPage().toGray()
        assertEquals(0, Orientation.estimate(page).rotationDegrees)
    }

    @Test
    fun `a sideways page is turned back upright`() {
        val up = TestImages.asymmetricTextPage()
        // rotate90 turns the picture clockwise, so undoing it takes another quarter turn.
        assertEquals(270, Orientation.estimate(Transforms.rotate90(up).toGray()).rotationDegrees)
        assertEquals(90, Orientation.estimate(Transforms.rotate270(up).toGray()).rotationDegrees)
    }

    @Test
    fun `an upside down page is turned back over`() {
        val up = TestImages.asymmetricTextPage()
        assertEquals(180, Orientation.estimate(Transforms.rotate180(up).toGray()).rotationDegrees)
    }

    @Test
    fun `applying the orientation fix makes the rows the strong axis`() {
        val up = TestImages.asymmetricTextPage()
        val sideways = Transforms.rotate90(up)
        val estimate = Orientation.estimate(sideways.toGray())

        val fixed = Transforms.rotate(sideways, estimate.rotationDegrees)
        val rowSharpness = Segmentation.profileSharpness(Segmentation.rowProfile(rowMask(fixed)))
        val columnSharpness = Segmentation.profileSharpness(Segmentation.columnProfile(rowMask(fixed)))
        assertTrue("rows $rowSharpness should beat columns $columnSharpness", rowSharpness > columnSharpness)
    }

    private fun rowMask(image: RgbaImage): GrayImage =
        Segmentation.inkMask(image.toGray(), radius = 15, bias = 10)

    // ------------------------------------------------------------------ skew

    @Test
    fun `level text has no skew`() {
        val page = TestImages.textPage().toGray()
        assertTrue(abs(Skew.estimate(page).degrees) < 0.5)
    }

    @Test
    fun `the reported tilt cancels the one applied`() {
        val page = TestImages.asymmetricTextPage()
        for (tilt in doubleArrayOf(3.5, -5.0)) {
            val tilted = Transforms.rotateArbitrary(page, tilt)
            val estimate = Skew.estimate(tilted.toGray())
            assertEquals("tilt $tilt", tilt, estimate.degrees, 1.2)

            val corrected = Transforms.rotateArbitrary(tilted, -estimate.degrees)
            val residual = abs(Skew.estimate(corrected.toGray()).degrees)
            assertTrue("residual $residual after correcting $tilt", residual < 1.0)
        }
    }

    // ------------------------------------------------------------------ segmentation

    @Test
    fun `connected components are labelled and sized`() {
        val image = TestImages.solid(120, 80, 0)
        for (y in 10 until 30) for (x in 10 until 40) image.set(x, y, 0xFFFFFFFF.toInt())
        for (y in 40 until 70) for (x in 60 until 100) image.set(x, y, 0xFFFFFFFF.toInt())

        val mask = Segmentation.brightMask(image.toGray(), 128)
        val labeled = Segmentation.label(mask, foregroundIsBright = true, minArea = 16)

        assertEquals("two blobs", 2, labeled.segments.size)
        val areas = labeled.segments.map { it.area }.sorted()
        assertEquals(20 * 30, areas[0])
        assertEquals(30 * 40, areas[1])
    }

    @Test
    fun `a rotated rectangle is fitted tightly`() {
        // A 60x30 bar rotated about its centre.
        val points = ArrayList<Pt>()
        val angle = Math.toRadians(30.0)
        for (i in 0..60) {
            for (j in intArrayOf(0, 30)) {
                val x = 20.0 + i - 30.0
                val y = 20.0 + j - 15.0
                points.add(Pt(x * Math.cos(angle) - y * Math.sin(angle) + 200, x * Math.sin(angle) + y * Math.cos(angle) + 200))
            }
        }
        val rect = Segmentation.minAreaRect(Segmentation.convexHull(points))
        assertTrue("a rectangle was fitted", rect != null)
        val e = rect!!.edgeLengths().sorted()
        assertEquals("short side", 30.0, e[0], 2.0)
        assertEquals("long side", 60.0, e[2], 2.0)
    }

    // ------------------------------------------------------------------ quality

    @Test
    fun `a blurred frame scores below a sharp one`() {
        val sharp = TestImages.asymmetricTextPage()
        val blurred = Blur.gaussianBlurRgba(sharp, 3.0)

        val sharpScore = Quality.analyse(sharp.toGray()).sharpness
        val blurredScore = Quality.analyse(blurred.toGray()).sharpness

        assertTrue("sharp $sharpScore vs blurred $blurredScore", sharpScore > blurredScore)
    }

    @Test
    fun `a dark frame is called out`() {
        val dark = TestImages.solid(200, 200, Px.rgb(12, 12, 12)).toGray()
        val report = Quality.analyse(dark)
        assertTrue("advice was ${report.advice}", report.advice == CaptureAdvice.TOO_DARK)
    }
}
