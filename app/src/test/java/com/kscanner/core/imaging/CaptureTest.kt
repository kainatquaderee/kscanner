package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The automatic shutter's gate, and the luma plane every frame it judges arrives in.
 *
 * The gate is the one place that decides a shot is taken without being asked, so it is tested on
 * its own terms: what counts as a new sighting of the same page, what counts as a different one,
 * and what the page has to be for the answer to be yes. The clock and the detections are arguments,
 * so none of this needs a camera or a sleep.
 */
class CaptureTest {

    /** A page region the gate could fire on: what [Quality.analysePage] reports for a good page. */
    private fun report(advice: CaptureAdvice = CaptureAdvice.GOOD) = Quality.Report(
        sharpness = 0.02,
        meanLuma = 180.0,
        contrast = 40.0,
        darkFraction = 0.01,
        brightFraction = 0.02,
        glareFraction = 0.0,
        illuminationRamp = 0.05,
        inkFraction = 0.05,
        advice = advice,
        detectedText = true,
    )

    /**
     * A page 300 px square, [dx] and [dy] from the frame's corner. Its own span is the mean
     * diagonal, 424 px, so the default 15 % corner tolerance is 63 px: a step of a hundred is a
     * different page, ten a detection is the same one still settling.
     */
    private fun page(dx: Double = 0.0, dy: Double = 0.0): Quad =
        Quad.rect(300.0, 300.0).translated(dx, dy)

    @Test
    fun `the gate fires on the detection that completes a stable run`() {
        val gate = CaptureGate(requiredRun = 3)

        assertTrue("one detection is not enough", !gate.observe(report(), page(), isDetection = true))
        assertTrue("two are not enough", !gate.observe(report(), page(), isDetection = true))
        assertTrue("the third fires", gate.observe(report(), page(), isDetection = true))
        assertEquals("and the page was acceptable throughout", CaptureAdvice.GOOD, gate.advice)
    }

    @Test
    fun `the frames between two detections are not observations of their own`() {
        val gate = CaptureGate(requiredRun = 2)

        assertTrue(!gate.observe(report(), page(), isDetection = true))
        // An analysis frame that asked for no outline has nothing new to say about where the page
        // is, and must not be counted as another sighting of it: the run would then be measured in
        // frames, ten times the unit it is supposed to be in.
        assertTrue("a frame, not a detection", !gate.observe(report(), null, isDetection = false))
        assertEquals(0.5, gate.readiness, 1e-9)
        assertTrue(gate.observe(report(), page(), isDetection = true))
    }

    @Test
    fun `a page being carried across the frame never fires`() {
        val gate = CaptureGate(requiredRun = 3)

        var fired = 0
        for (i in 0 until 12) {
            if (gate.observe(report(), page(dx = i * 100.0), isDetection = true)) fired++
        }

        assertEquals("moving is not settling", 0, fired)
        assertEquals("every step restarts the run", 1.0 / 3, gate.readiness, 1e-9)
    }

    @Test
    fun `a slow drift is one page held, a step is not`() {
        val drifting = CaptureGate(requiredRun = 5)
        for (i in 0 until 4) {
            assertTrue("drifted ${i * 10} px", !drifting.observe(report(), page(dx = i * 10.0), isDetection = true))
        }
        // Forty pixels in all, against an anchor whose own span allows sixty-three: the run is not
        // broken by a hand settling, which resetting against the *previous* detection would have.
        assertTrue("held long enough", drifting.observe(report(), page(dx = 40.0), isDetection = true))

        val stepped = CaptureGate(requiredRun = 3)
        stepped.observe(report(), page(), isDetection = true)
        stepped.observe(report(), page(), isDetection = true)
        assertTrue("a jump is a different page", !stepped.observe(report(), page(dx = 100.0), isDetection = true))
        assertEquals("which starts its own run", 1.0 / 3, stepped.readiness, 1e-9)
    }

    @Test
    fun `a page that leaves resets the run`() {
        val gate = CaptureGate(requiredRun = 3)
        gate.observe(report(), page(), isDetection = true)
        gate.observe(report(), page(), isDetection = true)

        assertTrue("nothing found", !gate.observe(report(CaptureAdvice.NO_DOCUMENT), null, isDetection = true))
        assertEquals("there is nothing to be steady about", 0.0, gate.readiness, 1e-9)
        assertTrue(!gate.observe(report(), page(), isDetection = true))
        assertTrue(!gate.observe(report(), page(), isDetection = true))
        assertTrue("only a fresh run fires", gate.observe(report(), page(), isDetection = true))
    }

    @Test
    fun `the gate stays shut during its cooldown`() {
        val gate = CaptureGate(requiredRun = 1, cooldownDetections = 3)

        assertTrue(gate.observe(report(), page(), isDetection = true))
        assertTrue("shot one", !gate.observe(report(), page(), isDetection = true))
        assertTrue("shot two", !gate.observe(report(), page(), isDetection = true))
        assertTrue("shot three", !gate.observe(report(), page(), isDetection = true))
        assertTrue("cooled down", gate.observe(report(), page(), isDetection = true))
    }

    @Test
    fun `a page turned and held again is shot once more, not in a burst`() {
        val gate = CaptureGate(requiredRun = 2, cooldownDetections = 1)

        assertTrue(!gate.observe(report(), page(), isDetection = true))
        assertTrue("the first page", gate.observe(report(), page(), isDetection = true))
        assertTrue("cooldown", !gate.observe(report(), page(), isDetection = true))
        assertTrue("the next page, arriving", !gate.observe(report(), page(dx = 400.0), isDetection = true))
        assertTrue("and held", gate.observe(report(), page(dx = 400.0), isDetection = true))
        assertTrue("once", !gate.observe(report(), page(dx = 400.0), isDetection = true))
    }

    @Test
    fun `a page that is held still but badly lit is not shot`() {
        for (bad in listOf(CaptureAdvice.TOO_DARK, CaptureAdvice.GLARE, CaptureAdvice.SHADOW, CaptureAdvice.BLURRY)) {
            val gate = CaptureGate(requiredRun = 2)
            var fired = false
            for (i in 0 until 6) {
                if (gate.observe(report(bad), page(), isDetection = true)) fired = true
            }

            assertTrue("$bad is not a shot", !fired)
            // The page has not moved, so the fault is the light and not the framing: the run is
            // complete and waiting, and the shot is taken as soon as the light comes back.
            assertEquals(1.0, gate.readiness, 1e-9)
            assertTrue("the light came back", gate.observe(report(), page(), isDetection = true))
        }
    }

    @Test
    fun `readiness is the stable run's progress toward firing`() {
        val gate = CaptureGate(requiredRun = 4)
        assertEquals(0.0, gate.readiness, 1e-9)
        gate.observe(report(), page(), isDetection = true)
        assertEquals(0.25, gate.readiness, 1e-9)
        gate.observe(report(), page(), isDetection = true)
        assertEquals(0.5, gate.readiness, 1e-9)
    }

    @Test
    fun `a manual shot restarts the sequence`() {
        val gate = CaptureGate(requiredRun = 2)
        gate.observe(report(), page(), isDetection = true)

        gate.reset()
        assertEquals(0.0, gate.readiness, 1e-9)
        assertEquals(CaptureAdvice.NO_DOCUMENT, gate.advice)
        assertTrue(!gate.observe(report(), page(), isDetection = true))
        assertTrue(gate.observe(report(), page(), isDetection = true))
    }

    // ------------------------------------------------------------------ luma plane

    @Test
    fun `a packed luma plane is read as-is`() {
        val width = 4
        val height = 3
        val buffer = ByteBuffer.allocate(width * height)
        for (i in 0 until width * height) buffer.put(i, (i * 10).toByte())

        val gray = Luma.grayFromPlane(buffer, width, height, rowStride = width)

        assertEquals(width, gray.width)
        assertEquals(height, gray.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals((y * width + x) * 10, gray.get(x, y))
            }
        }
    }

    @Test
    fun `row padding is skipped`() {
        val width = 3
        val height = 2
        val rowStride = 8 // five padding bytes per row
        val buffer = ByteBuffer.allocate(rowStride * height)
        // Fill padding with a value that must never be read.
        for (i in 0 until buffer.capacity()) buffer.put(i, 99.toByte())
        buffer.put(0, 10); buffer.put(1, 20); buffer.put(2, 30)
        buffer.put(rowStride, 40); buffer.put(rowStride + 1, 50); buffer.put(rowStride + 2, 60)

        val gray = Luma.grayFromPlane(buffer, width, height, rowStride)

        assertEquals(10, gray.get(0, 0))
        assertEquals(30, gray.get(2, 0))
        assertEquals(40, gray.get(0, 1))
        assertEquals(60, gray.get(2, 1))
    }

    @Test
    fun `interleaved pixels are strided over`() {
        val width = 2
        val height = 2
        val pixelStride = 2
        val buffer = ByteBuffer.allocate(width * pixelStride * height)
        // Luma at even offsets, junk (chroma) at odd ones.
        buffer.put(0, 11); buffer.put(1, 99.toByte())
        buffer.put(2, 22); buffer.put(3, 99.toByte())
        buffer.put(4, 33); buffer.put(5, 99.toByte())
        buffer.put(6, 44); buffer.put(7, 99.toByte())

        val gray = Luma.grayFromPlane(buffer, width, height, rowStride = width * pixelStride, pixelStride = pixelStride)

        assertEquals(11, gray.get(0, 0))
        assertEquals(22, gray.get(1, 0))
        assertEquals(33, gray.get(0, 1))
        assertEquals(44, gray.get(1, 1))
    }

    @Test
    fun `stepping downsamples to about the target edge`() {
        val width = 4000
        val height = 3000
        val step = Luma.stepFor(width, height, targetEdge = 400)

        assertEquals(10, step)
        assertEquals(400, (width + step - 1) / step)
        assertEquals(1, Luma.stepFor(300, 200, targetEdge = 400))
    }

    @Test
    fun `a stepped read keeps every tenth pixel`() {
        val width = 20
        val height = 10
        val buffer = ByteBuffer.allocate(width * height)
        for (i in 0 until width * height) buffer.put(i, i.toByte())

        val gray = Luma.grayFromPlane(buffer, width, height, rowStride = width, step = 10)

        assertEquals(2, gray.width)
        assertEquals(1, gray.height)
        assertEquals(0, gray.get(0, 0))
        assertEquals(10, gray.get(1, 0))
    }

    @Test
    fun `a sampled frame is described by the image that was sampled`() {
        // The camera's own 640x480 would describe an outline found here as half its real size, and
        // draw it half a frame away from where the page is. The frame a quad is in is the frame
        // that was searched, which is the sampled one.
        val sampled = Luma.sampleFrame(
            buffer = ByteBuffer.allocate(640 * 480),
            width = 640,
            height = 480,
            rowStride = 640,
            targetEdge = 320,
            rotationDegrees = 90,
        )

        assertEquals(320, sampled.gray.width)
        assertEquals(240, sampled.gray.height)
        assertEquals(320, sampled.frame.width)
        assertEquals(240, sampled.frame.height)
        assertEquals("the camera's turn still applies", 90, sampled.frame.rotationDegrees)
    }

    @Test
    fun `a frame small enough to be read whole is described as itself`() {
        val sampled = Luma.sampleFrame(
            buffer = ByteBuffer.allocate(300 * 200),
            width = 300,
            height = 200,
            rowStride = 300,
            targetEdge = 320,
        )

        assertEquals(1, Luma.stepFor(300, 200, targetEdge = 320))
        assertEquals(300, sampled.frame.width)
        assertEquals(200, sampled.frame.height)
        assertEquals(0, sampled.frame.rotationDegrees)
    }
}
