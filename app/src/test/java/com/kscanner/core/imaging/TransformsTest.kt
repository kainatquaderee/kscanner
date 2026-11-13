package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformsTest {

    private fun grid(): RgbaImage {
        // 3 wide, 2 tall:  A B C
        //                  D E F
        val img = RgbaImage(3, 2, IntArray(6))
        val values = intArrayOf(
            0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(),
            0xFFAA0000.toInt(), 0xFFAA00AA.toInt(), 0xFFAAAA00.toInt(),
        )
        values.copyInto(img.pixels)
        return img
    }

    @Test
    fun `rotate90 turns the image clockwise`() {
        val rotated = Transforms.rotate90(grid())
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)

        // Expected:  D A
        //            E B
        //            F C
        assertEquals(0xFFAA0000.toInt(), rotated.get(0, 0))
        assertEquals(0xFF0000AA.toInt(), rotated.get(1, 0))
        assertEquals(0xFFAA00AA.toInt(), rotated.get(0, 1))
        assertEquals(0xFF00AA00.toInt(), rotated.get(1, 1))
        assertEquals(0xFFAAAA00.toInt(), rotated.get(0, 2))
        assertEquals(0xFF00AAAA.toInt(), rotated.get(1, 2))
    }

    @Test
    fun `four quarter turns restore the original`() {
        val original = grid()
        val spun = Transforms.rotate90(
            Transforms.rotate90(
                Transforms.rotate90(Transforms.rotate90(original)),
            ),
        )
        assertEquals(original.width, spun.width)
        assertEquals(original.height, spun.height)
        assertTrue(original.pixels.contentEquals(spun.pixels))
    }

    @Test
    fun `rotate with zero degrees is a no-op that avoids a copy`() {
        val original = grid()
        assertSame(original, Transforms.rotate(original, 0))
    }

    @Test
    fun `rotate normalises negative and over-turned angles`() {
        val original = grid()
        assertTrue(original.pixels.contentEquals(Transforms.rotate(original, 360).pixels))
        assertTrue(Transforms.rotate90(original).pixels.contentEquals(Transforms.rotate(original, 450).pixels))
        assertTrue(Transforms.rotate(original, -90).pixels.contentEquals(Transforms.rotate(original, 270).pixels))
    }

    @Test
    fun `flipHorizontal mirrors columns`() {
        val flipped = Transforms.flipHorizontal(grid())
        assertEquals(0xFF00AAAA.toInt(), flipped.get(0, 0))
        assertEquals(0xFF0000AA.toInt(), flipped.get(2, 0))
        assertEquals(0xFFAAAA00.toInt(), flipped.get(0, 1))
    }

    @Test
    fun `resize keeps a flat colour flat`() {
        val flat = TestImages.solid(40, 20, 0xFF123456.toInt())
        val resized = Transforms.resize(flat, 100, 50)
        assertEquals(100, resized.width)
        assertEquals(50, resized.height)
        for (p in resized.pixels) assertEquals(0xFF123456.toInt(), p)
    }

    @Test
    fun `fitLongestEdge never upscales`() {
        val small = TestImages.solid(50, 40, 0)
        assertSame(small, Transforms.fitLongestEdge(small, 200))

        val large = TestImages.solid(800, 600, 0)
        val fitted = Transforms.fitLongestEdge(large, 400)
        assertEquals(400, fitted.width)
        assertEquals(300, fitted.height)
    }

    @Test
    fun `rotating a corner list keeps it aligned with the rotated pixels`() {
        val quad = Quad(Pt(0.0, 0.0), Pt(2.0, 0.0), Pt(2.0, 1.0), Pt(0.0, 1.0))
        val rotatedPixel = Transforms.rotate90(grid()) // 2 x 3
        val rotatedQuad = Transforms.rotateQuad90(quad, 3, 2)

        assertTrue(rotatedQuad.isConvex())
        // Corner coordinates span (size - 1), matching the pixel-centre convention.
        assertEquals((rotatedPixel.width - 1).toDouble(), rotatedQuad.tr.x - rotatedQuad.tl.x, 1e-9)
        assertEquals((rotatedPixel.height - 1).toDouble(), rotatedQuad.bl.y - rotatedQuad.tl.y, 1e-9)
    }
}
