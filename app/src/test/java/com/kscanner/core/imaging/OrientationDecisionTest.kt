package com.kscanner.core.imaging

import com.kscanner.core.imaging.Orientation.FlipEvidence
import com.kscanner.core.imaging.Orientation.FlipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0-versus-180 decision on its own, with no image in sight.
 *
 * The rows are the design's five: they pin the abstention rule (a member that offered no
 * reading can never decide the flip, and never lets the other member decide it alone), the
 * strict margin on both sides, and the case where the two candidates say exactly the same
 * thing. The numbers are shapes, not calibration: the calibration lives on the fixtures.
 */
class OrientationDecisionTest {

    private fun measured(mean: Double) =
        FlipEvidence(mean = mean, bands = 16, samples = 16, sumSquares = 16 * 0.02 * 0.02)

    /** The same reading carried by two bands instead of a page's worth: a different margin. */
    private fun sparse(mean: Double) =
        FlipEvidence(mean = mean, bands = 2, samples = 2, sumSquares = 2 * 0.02 * 0.02)

    @Test
    fun `an abstaining as-captured candidate leaves the frame as captured`() {
        val decision = Orientation.decideFlip(FlipEvidence.NONE, measured(0.06))

        assertFalse("no flip on half the evidence", decision.flip)
        assertFalse("half the evidence decides nothing", decision.decided)
        assertEquals(FlipReason.ONE_SIDE_ABSTAINED, decision.reason)
        assertTrue("the margin is still reported", decision.margin > 0.0)
    }

    @Test
    fun `an abstaining turned candidate leaves the frame as captured`() {
        val decision = Orientation.decideFlip(measured(0.06), FlipEvidence.NONE)

        assertFalse("no flip on half the evidence", decision.flip)
        assertFalse("half the evidence decides nothing", decision.decided)
        assertEquals(FlipReason.ONE_SIDE_ABSTAINED, decision.reason)
        assertTrue("the margin is still reported", decision.margin > 0.0)
    }

    @Test
    fun `a clear gap the turning way flips the page`() {
        val decision = Orientation.decideFlip(measured(-0.05), measured(0.05))

        assertTrue("the gap is the turning way", decision.flip)
        assertTrue("the gap cleared the margin", decision.decided)
        assertEquals(FlipReason.FLIPPED, decision.reason)
        assertTrue("the gap is reported", decision.gap > decision.margin)
    }

    @Test
    fun `a clear gap the keeping way keeps the page as captured`() {
        val decision = Orientation.decideFlip(measured(0.021), measured(-0.021))

        assertFalse("a decided page can still stay as captured", decision.flip)
        assertTrue("the gap cleared the margin", decision.decided)
        assertEquals(FlipReason.KEPT, decision.reason)
        assertTrue("the gap is the keeping way", decision.gap < -decision.margin)
    }

    @Test
    fun `two candidates that agree decide nothing`() {
        val decision = Orientation.decideFlip(measured(0.0), measured(0.0))

        assertFalse("no flip", decision.flip)
        assertFalse("no evidence either way", decision.decided)
        assertEquals(FlipReason.BELOW_NOISE, decision.reason)
        assertEquals("the gap of two equal readings", 0.0, decision.gap, 0.0)
    }

    /**
     * The other no-evidence shape: neither candidate has a reading at all. The two-signal rule plus
     * this one are the whole of AC-13 at the pure-function level — an absent reading can never be
     * counted as a reading of 0.0, which is refuted here.
     */
    @Test
    fun `two candidates that both abstain decide nothing`() {
        val decision = Orientation.decideFlip(FlipEvidence.NONE, FlipEvidence.NONE)

        assertFalse("nothing was read on either side", decision.decided)
        assertFalse("so nothing is turned", decision.flip)
        assertEquals(FlipReason.ONE_SIDE_ABSTAINED, decision.reason)
        assertEquals("no reading on either side means no gap", 0.0, decision.gap, 0.0)
    }

    /**
     * Rule 2 of [Orientation.decideFlip]: a candidate carrying fewer than three bands only gets two
     * bands' word, so the margin widens. Nothing in the suite covered that branch — every fixture
     * above carries sixteen bands — and it is the difference between a sparse page being believed
     * and being left alone.
     *
     * Both margins are read back from the function rather than hardcoded, so a legitimate re-tune
     * does not make this a false failure; what it pins is the *relationship*: a gap strictly between
     * the two margins is a decision on three bands and a refusal on two.
     */
    @Test
    fun `a gap between the two margins is believed on three bands and refused on two`() {
        val proseMargin = Orientation.decideFlip(measured(0.0), measured(0.0)).margin
        val sparseMargin = Orientation.decideFlip(sparse(0.0), sparse(0.0)).margin
        assertTrue(
            "two bands' word is worth less than a page of prose's ($sparseMargin vs $proseMargin)",
            sparseMargin > proseMargin,
        )

        val gap = (proseMargin + sparseMargin) / 2.0

        val onTwo = Orientation.decideFlip(sparse(-gap / 2), sparse(gap / 2))
        assertEquals("the fixture's gap", gap, onTwo.gap, 1e-12)
        assertFalse("two bands may not decide on this gap", onTwo.decided)
        assertEquals(FlipReason.BELOW_NOISE, onTwo.reason)

        val onSixteen = Orientation.decideFlip(measured(-gap / 2), measured(gap / 2))
        assertEquals("the same gap", gap, onSixteen.gap, 1e-12)
        assertTrue("three bands decide on the same gap", onSixteen.decided)
        assertEquals(FlipReason.FLIPPED, onSixteen.reason)
    }

    /**
     * Rule 3: the gap has to clear the margin *strictly*. A gap exactly one margin wide is the
     * widest gap that still decides nothing, and it is the boundary a "<" instead of a "<=" would
     * silently move.
     */
    @Test
    fun `a gap exactly on the margin is not a decision`() {
        val margin = Orientation.decideFlip(measured(0.0), measured(0.0)).margin
        val decision = Orientation.decideFlip(measured(-margin / 2), measured(margin / 2))

        assertEquals("the gap is exactly one margin", margin, decision.gap, 1e-12)
        assertFalse("a gap on the margin is not a decision", decision.decided)
        assertFalse("so the page is not turned", decision.flip)
        assertEquals(FlipReason.BELOW_NOISE, decision.reason)
    }

    /**
     * Degenerate input rather than a fixture. A reading of NaN is *not* "no reading" — `bands` is
     * non-zero, so [FlipEvidence.measured] is true and the value reaches the arithmetic. The one
     * thing that must be true of it is that it can never turn a page: every comparison against NaN
     * is false, which lands the decision on "kept as captured". It must also not throw while
     * reporting the scatter it computed from it.
     *
     * Recorded limit: NaN propagates into [Orientation.FlipDecision.standardError], which is
     * reported but does not set the margin (the kill-criterion note in `decideFlip`), so this is
     * harmless today. A future change that made `standardError` raise the margin would have to
     * decide what a NaN scatter means, and this test would notice.
     */
    @Test
    fun `a reading that is not a number cannot turn the page`() {
        val nan = FlipEvidence(mean = Double.NaN, bands = 16, samples = 16, sumSquares = Double.NaN)

        val decision = Orientation.decideFlip(nan, measured(0.06))

        assertFalse("a NaN reading is not evidence to turn a page", decision.flip)
        assertFalse("and it decides nothing", decision.decided)
        assertEquals(FlipReason.BELOW_NOISE, decision.reason)
        assertTrue(
            "the scatter it reports is not a number, and that is reported rather than hidden",
            decision.standardError.isNaN(),
        )
    }
}
