package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band extent the flip cue reads, pinned on a hand-built profile.
 *
 * No image, no mask, no fixture: [Orientation.textBands] takes a row profile and returns one
 * range per line of print. The rule it has to keep is in [Orientation]'s own KDoc — the ink
 * sits nearer the baseline than the band centre because ascenders stretch the band upward
 * further than descenders stretch it down — and a band that stops at the x-height run cannot
 * carry that asymmetry at all, which is the defect this seam exists to prevent from returning.
 */
class OrientationBandsTest {

    /**
     * A line as the profile of real type shows it: a narrow ascender shoulder above the
     * x-height run, a narrow descender skirt below it, then blank paper.
     */
    private fun line(profile: IntArray, top: Int) {
        // two ascender rows, five x-height rows, two descender rows
        val rows = intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20)
        for (i in rows.indices) profile[top + i] = rows[i]
    }

    private fun profile(size: Int, vararg lines: Pair<Int, IntArray>): IntArray {
        val out = IntArray(size)
        for ((top, rows) in lines) for (i in rows.indices) out[top + i] = rows[i]
        return out
    }

    @Test
    fun `a band carries the ascender and descender rows, not just the x-height core`() {
        val profile = IntArray(40)
        line(profile, top = 4)
        line(profile, top = 20)

        val bands = Orientation.textBands(profile)
        assertEquals("two lines of print", 2, bands.size)

        val first = bands[0]
        assertEquals("the band starts on the ascender row", 4, first.first)
        assertEquals("the band ends on the descender row", 12, first.last)
        // The excursion inside the band is the cue: the ascender shoulder is narrower than the
        // descender skirt only above the baseline, so the centroid sits below the band centre.
        assertTrue("the ascender shoulder is inside the band", first.first < 6)
        assertTrue("the descender skirt is inside the band", first.last > 10)
    }

    @Test
    fun `a band stops at the paper before the next line`() {
        val profile = IntArray(40)
        line(profile, top = 4)
        line(profile, top = 20)

        val bands = Orientation.textBands(profile)
        assertEquals("the band stops short of the blank rows", 12, bands[0].last)
        assertEquals("the next line starts on its own ascender row", 20, bands[1].first)
        assertTrue("nothing between the lines is claimed", bands[0].last < bands[1].first)
    }

    @Test
    fun `two lines on tight leading do not produce overlapping bands`() {
        // One row of paper between the descender of the first line and the ascender of the
        // second: the skirts meet, and the midpoint cap is the only thing keeping the two
        // bands apart.
        val profile = profile(
            40,
            4 to intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20),
            14 to intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20),
        )

        val bands = Orientation.textBands(profile)
        assertEquals("two lines of print", 2, bands.size)
        for (i in 1 until bands.size) {
            assertTrue(
                "bands ${bands[i - 1]} and ${bands[i]} overlap",
                bands[i - 1].last < bands[i].first,
            )
        }
    }

    @Test
    fun `a band that would merge on continuous ink still splits at the valley`() {
        // No blank row at all between the two lines: the profile descends from the first
        // x-height core into a valley that is above the paper floor, and climbs again.
        val profile = profile(
            40,
            4 to intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20, 40),
            15 to intArrayOf(40, 20, 30, 100, 110, 120, 110, 100, 30, 20),
        )

        val bands = Orientation.textBands(profile)
        assertEquals("the valley separates the two lines", 2, bands.size)
        assertTrue("no overlap at the valley", bands[0].last < bands[1].first)
    }

    @Test
    fun `a profile with no ink has no bands`() {
        assertEquals(emptyList<IntRange>(), Orientation.textBands(IntArray(32)))
    }

    // ------------------------------------------------------------ adversarial profiles

    @Test
    fun `an empty profile has no bands`() {
        assertEquals(emptyList<IntRange>(), Orientation.textBands(IntArray(0)))
    }

    @Test
    fun `a single inked row is not a band`() {
        // `bands()` needs two consecutive rows above the cut before it will call anything a line of
        // print, so a one-row spike — dust on the platen, a hair — must not become a band wherever
        // in the frame it lands.
        for (at in intArrayOf(0, 7, 31)) {
            val profile = IntArray(32)
            profile[at] = 100
            assertEquals(
                "a lone inked row at $at is not a line of print",
                emptyList<IntRange>(),
                Orientation.textBands(profile),
            )
        }
    }

    @Test
    fun `a band whose ascender row is the first row reports the edge`() {
        // Print set so close to the crop that its ascender shoulder is row 0. Growth stops at the
        // frame, so the band starts at 0 rather than clamping inward and pretending to a boundary
        // that is not there — which is what lets the caller reject it. `flipBias` does exactly that,
        // because at the page boundary the ink is the dark surface behind the sheet, not type.
        val profile = IntArray(24)
        line(profile, top = 0)

        val bands = Orientation.textBands(profile)
        assertEquals("one line of print", 1, bands.size)
        assertEquals("the band runs to the frame edge", 0, bands[0].first)
        assertTrue("and it was grown past its x-height core, not clamped", bands[0].last > 6)
    }

    @Test
    fun `a profile where every row is above the cut is one unbroken band`() {
        // Solid ink with no valley anywhere: there is no row below the cut to separate one line from
        // the next, so the core is the whole frame and the band is the whole frame. `textBands`
        // reports the geometry it sees and leaves the interpretation to its caller — a band running
        // to both edges is rejected there, which is why a page of solid ink decides no flip.
        val bands = Orientation.textBands(IntArray(20) { 100 })

        assertEquals("one unbroken band", 1, bands.size)
        assertEquals("it starts at the top of the frame", 0, bands[0].first)
        assertEquals("and ends at the bottom", 19, bands[0].last)
    }

    @Test
    fun `bands stay inside the profile, in order, and never overlap`() {
        // The invariant the growth arithmetic can break in the ways that matter: a band that walks
        // off either end of the array, an empty or inverted range, or two bands sharing a row. None
        // of these profiles is a page, which is the point.
        val profiles = listOf(
            "empty" to IntArray(0),
            "no ink" to IntArray(16),
            "solid ink" to IntArray(20) { 100 },
            "a lone row" to IntArray(32).also { it[7] = 100 },
            "lines on both frame edges" to profile(
                24,
                0 to intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20),
                15 to intArrayOf(20, 30, 100, 110, 120, 110, 100, 30, 20),
            ),
        )

        for ((what, p) in profiles) {
            var previousEnd = -1
            for (band in Orientation.textBands(p)) {
                assertTrue("$what: band $band starts before the profile", band.first >= 0)
                assertTrue("$what: band $band ends past the profile of ${p.size} rows", band.last < p.size)
                assertTrue("$what: band $band is empty or inverted", band.first <= band.last)
                assertTrue(
                    "$what: band $band overlaps the previous band ending at $previousEnd",
                    band.first > previousEnd,
                )
                previousEnd = band.last
            }
        }
    }
}
