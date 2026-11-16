package com.kscanner.core.imaging

import java.nio.ByteBuffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journey a live frame actually takes: the camera's luma plane, sampled small, detected, and
 * drawn on a phone-shaped preview.
 *
 * This is the only test in the suite that starts from a camera-sized plane, and it exists because
 * v1.6.0 — the release that first drew the border — shipped with the two ends of that journey
 * disagreeing about which frame the outline was in. The detector answered in the sampled image's
 * pixels; the drawing side was told the camera's size. Every border was drawn at `1/step` of its
 * size about the wrong centre, so it sat beside the page and, on a tall screen, off the edge of it.
 *
 * The expected positions are not taken from the code under test: they are [pageInFrame], the quad
 * the fixture page was photographed through, carried through the same rotation and layout a phone
 * gives it.
 */
class LiveOutlineTest {

    /** Where the page lies in the camera's frame, in the frame's own pixels. */
    private val pageInFrame = Quad(
        Pt(60.0, 20.0),
        Pt(580.0, 40.0),
        Pt(560.0, 460.0),
        Pt(80.0, 440.0),
    )

    private val cameraWidth = 640
    private val cameraHeight = 480

    /** Taller than the frame's 4:3, as every phone's preview is: the fill crops the frame's sides. */
    private val viewWidth = 1080
    private val viewHeight = 2400

    /** A page photographed into a camera-sized frame, as the luma plane would arrive. */
    private fun cameraFrame(): ByteBuffer {
        val page = RealisticPages.prosePage(900, 1200)
        val camera = RealisticPages.photograph(page, cameraWidth, cameraHeight, pageInFrame).toGray()
        return ByteBuffer.allocate(camera.width * camera.height).apply {
            for (v in camera.data) put(v.toByte())
            rewind()
        }
    }

    /** What the analyser does with one frame: sample it, and keep the sample's own frame. */
    private fun sampled(rotationDegrees: Int): Luma.SampledFrame = Luma.sampleFrame(
        buffer = cameraFrame(),
        width = cameraWidth,
        height = cameraHeight,
        rowStride = cameraWidth,
        targetEdge = 320,
        rotationDegrees = rotationDegrees,
    )

    private fun foundIn(frame: Luma.SampledFrame): Detection {
        val found = DocDetector.detect(frame.gray, allowFallbacks = false)
        assertNotNull("the fixture page was not found", found)
        return found!!
    }

    /** Where the page is drawn on the preview, from the quad it was photographed through. */
    private fun pageOnScreen(rotationDegrees: Int): Quad = FrameGeometry.toView(
        quad = pageInFrame,
        frame = PreviewFrame(cameraWidth, cameraHeight, rotationDegrees),
        viewWidth = viewWidth,
        viewHeight = viewHeight,
    )

    private fun worstCornerGap(drawn: Quad, onScreen: Quad): Double {
        var worst = 0.0
        for (i in 0 until 4) worst = maxOf(worst, drawn.points[i].dist(onScreen.points[i]))
        return worst
    }

    @Test
    fun `the border is drawn on the page, at every rotation the camera reports`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val frame = sampled(rotation)
            val drawn = FrameGeometry.toView(foundIn(frame).quad, frame.frame, viewWidth, viewHeight)
            val gap = worstCornerGap(drawn, pageOnScreen(rotation))
            // 20 px: the detector's own few-pixel error at a sampled edge of 320, and nothing like
            // the several hundred a frame-size mismatch costs. The trailing check is the point.
            assertTrue("rotation $rotation is $gap px off the page", gap < 20.0)
        }
    }

    @Test
    fun `the camera's size for a sampled outline is the defect this release fixes`() {
        val frame = sampled(rotationDegrees = 90)
        val found = foundIn(frame)

        // What v1.6.0 drew: the same quad, described as if it filled the camera's 640x480 frame.
        val asShipped = FrameGeometry.toView(
            quad = found.quad,
            frame = PreviewFrame(cameraWidth, cameraHeight, 90),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )

        val gap = worstCornerGap(asShipped, pageOnScreen(90))
        assertTrue("the mismatched frame was only $gap px out", gap > 200.0)
    }
}
