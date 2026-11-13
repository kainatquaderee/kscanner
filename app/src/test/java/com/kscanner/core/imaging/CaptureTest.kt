package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CaptureTest {

    private fun report(
        sharpness: Double = 0.2,
        advice: CaptureAdvice = CaptureAdvice.GOOD,
    ) = Quality.Report(
        sharpness = sharpness,
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

    @Test
    fun `the gate wants several good frames in a row`() {
        val gate = CaptureGate(requiredFrames = 3, cooldownFrames = 2)

        assertTrue("one frame is not enough", !gate.observe(report()))
        assertTrue("two frames are not enough", !gate.observe(report()))
        assertTrue("the third fires", gate.observe(report()))
    }

    @Test
    fun `a bad frame resets the streak`() {
        val gate = CaptureGate(requiredFrames = 3, cooldownFrames = 0)

        gate.observe(report())
        gate.observe(report())
        gate.observe(report(advice = CaptureAdvice.BLURRY))
        assertTrue("the streak restarted", !gate.observe(report()))
        assertTrue("(and again)", !gate.observe(report()))
        assertTrue("only now", gate.observe(report()))
    }

    @Test
    fun `borderline sharpness is not enough for an automatic shot`() {
        val gate = CaptureGate(requiredFrames = 1)
        val borderline = Quality.SHARPNESS_GOOD - 0.01
        assertTrue("borderline rejected", !gate.observe(report(sharpness = borderline)))
        assertTrue("clearly sharp accepted", gate.observe(report(sharpness = Quality.SHARPNESS_GOOD + 0.02)))
    }

    @Test
    fun `the gate stays shut during its cooldown`() {
        val gate = CaptureGate(requiredFrames = 1, cooldownFrames = 3)

        assertTrue(gate.observe(report()))
        assertTrue("shot one", !gate.observe(report()))
        assertTrue("shot two", !gate.observe(report()))
        assertTrue("shot three", !gate.observe(report()))
        assertTrue("cooled down", gate.observe(report()))
    }

    @Test
    fun `readiness fills as the streak grows`() {
        val gate = CaptureGate(requiredFrames = 4)
        assertEquals(0.0, gate.readiness, 1e-9)
        gate.observe(report())
        assertEquals(0.25, gate.readiness, 1e-9)
        gate.observe(report())
        assertEquals(0.5, gate.readiness, 1e-9)
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
}
