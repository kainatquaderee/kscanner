package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The arithmetic that puts a detected outline on top of the preview. Every expected value here
 * is worked out by hand from the camera's own conventions, not from the code under test.
 */
class FrameGeometryTest {

    /** A lopsided quad inside a 4x6 frame, so a mirrored or mislabelled turn cannot pass. */
    private val detected = Quad(
        Pt(1.0, 2.0),
        Pt(2.0, 2.0),
        Pt(2.0, 4.0),
        Pt(1.0, 4.0),
    )

    private val frame = PreviewFrame(width = 4, height = 6, rotationDegrees = 0)

    private fun assertQuad(expected: Quad, actual: Quad, message: String = "") {
        val corners = listOf("tl", "tr", "br", "bl")
        expected.points.forEachIndexed { i, p ->
            assertEquals("$message ${corners[i]}.x", p.x, actual.points[i].x, 1e-9)
            assertEquals("$message ${corners[i]}.y", p.y, actual.points[i].y, 1e-9)
        }
    }

    @Test
    fun `a frame that needs no turn is left alone`() {
        assertEquals(Pair(4, 6), FrameGeometry.orientedSize(frame))
        assertQuad(detected, FrameGeometry.orient(detected, frame))
    }

    @Test
    fun `a quarter turn sends each corner to the next one`() {
        // (x, y) -> (5 - y, x) in a 4x6 frame: tl -> tr, tr -> br, br -> bl, bl -> tl.
        val expected = Quad(
            Pt(1.0, 1.0),
            Pt(3.0, 1.0),
            Pt(3.0, 2.0),
            Pt(1.0, 2.0),
        )

        val turned = FrameGeometry.orient(detected, frame.copy(rotationDegrees = 90))

        assertQuad(expected, turned)
        assertEquals("an upright 4x6 frame stands 6x4", Pair(6, 4), FrameGeometry.orientedSize(frame.copy(rotationDegrees = 90)))
    }

    @Test
    fun `two quarter turns are a half turn`() {
        // (x, y) -> (3 - x, 5 - y) in a 4x6 frame: the old br becomes the new tl, and the
        // corners are relabelled so tl -> tr -> br -> bl still walks the outline.
        val expected = Quad(
            Pt(1.0, 1.0),
            Pt(2.0, 1.0),
            Pt(2.0, 3.0),
            Pt(1.0, 3.0),
        )

        assertQuad(expected, FrameGeometry.orient(detected, frame.copy(rotationDegrees = 180)))
        assertEquals(Pair(4, 6), FrameGeometry.orientedSize(frame.copy(rotationDegrees = 180)))
    }

    @Test
    fun `three quarter turns are one turn the other way`() {
        // (x, y) -> (y, 3 - x): 270 clockwise is the same as 90 anticlockwise.
        val expected = Quad(
            Pt(2.0, 1.0),
            Pt(4.0, 1.0),
            Pt(4.0, 2.0),
            Pt(2.0, 2.0),
        )

        assertQuad(expected, FrameGeometry.orient(detected, frame.copy(rotationDegrees = 270)))
        assertEquals(Pair(6, 4), FrameGeometry.orientedSize(frame.copy(rotationDegrees = 270)))
    }

    @Test
    fun `turns are counted modulo a full turn`() {
        assertQuad(detected, FrameGeometry.orient(detected, frame.copy(rotationDegrees = 360)))
        assertQuad(
            FrameGeometry.orient(detected, frame.copy(rotationDegrees = 270)),
            FrameGeometry.orient(detected, frame.copy(rotationDegrees = -90)),
        )
    }

    @Test
    fun `a half turn is not the identity`() {
        // Guards the whole file: if the turn were a no-op, the tests above would still pass on
        // a symmetric fixture but not on this one.
        assertNotEquals(detected, FrameGeometry.orient(detected, frame.copy(rotationDegrees = 180)))
    }

    @Test
    fun `filling the view scales by the wider ratio and centres what is left over`() {
        // A 4x6 frame in a square view: width needs 25x, height needs 16.67x. Scaling by 25
        // makes the frame 100 wide and 150 tall, so 25 px hang off the top and the bottom.
        val map = FrameGeometry.fillCenter(frameWidth = 4, frameHeight = 6, viewWidth = 100, viewHeight = 100)

        assertEquals(25.0, map.scale, 1e-9)
        assertEquals(0.0, map.dx, 1e-9)
        assertEquals(-25.0, map.dy, 1e-9)
        assertEquals(Pt(0.0, -25.0), map.map(Pt(0.0, 0.0)))
        assertEquals(Pt(100.0, 125.0), map.map(Pt(4.0, 6.0)))
    }

    @Test
    fun `a view with the frame's aspect ratio needs no cropping`() {
        val map = FrameGeometry.fillCenter(frameWidth = 4, frameHeight = 6, viewWidth = 40, viewHeight = 60)

        assertEquals(10.0, map.scale, 1e-9)
        assertEquals(Pt(0.0, 0.0), map.map(Pt(0.0, 0.0)))
        assertEquals(Pt(40.0, 60.0), map.map(Pt(4.0, 6.0)))
    }

    @Test
    fun `a view that has not been measured yet maps nothing`() {
        assertEquals(FrameMap.IDENTITY, FrameGeometry.fillCenter(4, 6, 0, 0))
        assertEquals(FrameMap.IDENTITY, FrameGeometry.fillCenter(0, 0, 100, 100))
    }

    @Test
    fun `a detected outline lands on the preview where it was photographed`() {
        // Frame delivered rotated a quarter turn, so it stands 6x4; the view is 60x40, the same
        // aspect, so the scale is 10 with nothing cropped.
        val view = Quad(
            Pt(10.0, 10.0),
            Pt(30.0, 10.0),
            Pt(30.0, 20.0),
            Pt(10.0, 20.0),
        )

        val mapped = FrameGeometry.toView(
            quad = detected,
            frame = frame.copy(rotationDegrees = 90),
            viewWidth = 60,
            viewHeight = 40,
        )

        assertQuad(view, mapped)
    }

    @Test
    fun `a half turn of the phone does not move the outline on screen`() {
        // Turning the device restarts the camera with the frame stored the other way up; the
        // outline has to come back to the same place on screen.
        val portrait = PreviewFrame(width = 4, height = 6, rotationDegrees = 90)
        val landscape = PreviewFrame(width = 6, height = 4, rotationDegrees = 0)

        assertQuad(
            FrameGeometry.toView(detected, portrait, 60, 40),
            FrameGeometry.toView(Transforms.rotateQuad90(detected, 4, 6), landscape, 60, 40),
        )
    }
}
