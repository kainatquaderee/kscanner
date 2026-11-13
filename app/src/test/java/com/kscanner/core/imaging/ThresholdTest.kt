package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdTest {

    @Test
    fun `otsu splits a bimodal image near the midpoint`() {
        val gray = GrayImage(100, 100, IntArray(10000) { if (it % 100 < 50) 30 else 220 })
        val threshold = Threshold.otsu(gray)
        // Every threshold inside the empty gap is equally optimal for Otsu, so assert the
        // separating property rather than one specific value.
        assertTrue("threshold $threshold should separate the modes", threshold in 30..219)
    }

    @Test
    fun `adaptive mean produces a clean bi-level image that keeps dark text`() {
        // Light background with a dark square, plus a strong brightness ramp so a global
        // threshold would fail on one side.
        val gray = GrayImage(200, 200, IntArray(40000) { i ->
            val x = i % 200
            val base = 120 + (x * 100 / 200)
            if (x in 90..110) 40 else base
        })
        val biLevel = Threshold.adaptiveMean(gray, radius = 12, bias = 10)

        for (v in biLevel.data) {
            assertTrue("expected bi-level output, saw $v", v == 0 || v == 255)
        }
        // The dark stripe must remain dark even where the background ramps up.
        assertEquals(0, biLevel.get(100, 100))
        assertEquals(255, biLevel.get(20, 100))
        assertEquals(255, biLevel.get(180, 100))
    }

    @Test
    fun `illumination normalisation flattens a gradient`() {
        val gray = GrayImage(200, 200, IntArray(40000) { i ->
            val x = i % 200
            80 + (x * 120 / 200) // 80 -> 200 ramp
        })
        val flattened = Threshold.normalizeIllumination(gray, radius = 40)

        // Spread across a row should collapse dramatically.
        val rowBefore = (0 until 200).map { gray.get(it, 100) }
        val rowAfter = (0 until 200).map { flattened.get(it, 100) }
        val spreadBefore = rowBefore.max() - rowBefore.min()
        val spreadAfter = rowAfter.max() - rowAfter.min()
        assertTrue("before=$spreadBefore after=$spreadAfter", spreadAfter < spreadBefore / 3)
    }

    @Test
    fun `apply range maps the endpoints onto the full scale`() {
        val gray = GrayImage(3, 1, intArrayOf(50, 100, 150))
        val stretched = Threshold.applyRange(gray, 50, 150)
        assertEquals(0, stretched.get(0, 0))
        assertEquals(128, stretched.get(1, 0))
        assertEquals(255, stretched.get(2, 0))
    }

    @Test
    fun `apply range is a no-op when the range is degenerate`() {
        val gray = GrayImage(3, 1, intArrayOf(50, 100, 150))
        val same = Threshold.applyRange(gray, 100, 100)
        assertEquals(50, same.get(0, 0))
        assertEquals(100, same.get(1, 0))
        assertEquals(150, same.get(2, 0))
    }

    @Test
    fun `stretch raises the contrast of a washed out image`() {
        val gray = GrayImage(100, 100, IntArray(10000) { 100 + (it % 20) })
        val stretched = Threshold.stretch(gray, 0.01, 0.99)
        assertTrue(stretched.data.max() - stretched.data.min() > 200)
    }
}
