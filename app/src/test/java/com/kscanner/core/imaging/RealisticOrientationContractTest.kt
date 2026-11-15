package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0-versus-180 contract, asserted on pages shaped like the ones a phone delivers.
 *
 * [AutoCropOrientationTest] states the same contract, but every one of its pages is built from
 * [TestImages.asymmetricTextPage], whose rows are trapezoids widening monotonically from 45% to
 * 100% of the line. That ramps the ink mass *inside* each band, which is the largest displacement a
 * band can carry, and it is not what a page of type looks like: in real type the ink within the
 * x-height run is spread by vertical stems and is close to uniform in y. So the existing fixtures
 * cannot fail the way a real capture fails, and these do.
 *
 * The seam is the shipped one. `AutoCrop.plan(.., detectOutline = false)` measures orientation on a
 * full-frame page exactly as the app does after it has flattened the outline to a 720 px page —
 * [AutoCrop] calls `Orientation.estimate(level(page))` either way — without dragging the line
 * detector's own error into an assertion that is about orientation. No emulator, no device, no
 * camera: the lighting below is a model of a lamp and a shadow, applied by arithmetic in
 * [RealisticPages], and that limit is stated in the report.
 *
 * ## What the cue is held to, and why it is no longer "every page is turned back"
 *
 * The contract this file asserted when it was written was stronger than the cue can meet: that an
 * upside-down page is *always* turned back upright. Four assertions could not be met by any
 * threshold, and the reason is structural, not a tuning failure. Measured across working edges
 * 520 (shipped), 780, 1040 and 1300, the flip cue decides an upside-down page of prose at 780 and
 * 1040 and abstains at 520 and 1300; at 780 it clears the majority gate by one band of twenty
 * (11 needed 10), and the same page swings 3.8x on a lighting change alone. The bias is measured
 * in band-height units, so more resolution adds pixels, not typographic asymmetry. There is no
 * affordable resolution at which this statistic is reliable on prose.
 *
 * That sweep was re-run independently for this file by driving the app's own path — the fixture
 * fitted to the 1080 preview edge, then the cue at each of the four edges — and it reproduces: 4 of
 * 7 prose fixtures decided at 520, 780 and 1040 and 0 of 7 at 1300, with a different four each time
 * (at 780 it is the prose and the stamped page that decide and the uneven-light and shadowed pages
 * that stop; at 1040 the stamped page misses the margin by 0.0002). Two clauses above are quoted
 * from the developer's measurement rather than reproduced here: the band *count* behind the majority
 * gate, which is not observable from the public API, and the 3.8x lighting ratio, which this run
 * did not reproduce (on these fixtures a lighting change moves the cue between "no reading at all"
 * and a reading, which is a larger effect than a ratio).
 *
 * So the contract here is the one the product actually ships, in three parts:
 *
 *  1. **A page is never left upside down silently.** An upside-down page is either turned back
 *     upright, or the pass reports that it could not tell — `AutoCrop.Plan.flipDecided` is false —
 *     and the batch review offers the one-tap flip. A page left upside down while reporting
 *     `flipDecided = true` is the silent failure this file exists to catch.
 *  2. **A decision that was made is the right one.** Where the pass did decide, it decided
 *     correctly, in both orientations.
 *  3. **No decision on no evidence.** Ink that carries no 0-versus-180 reading at all — a boxed
 *     table, a solid block — is declined rather than guessed at.
 *
 * A fixture that comes out *undecided and flagged* is not a failing fixture: it is exactly what
 * part 1 permits, and it is why the review banner exists. The tests below that still demand a
 * 180-degree turn are the ones the cue really can deliver, and they are the ones protecting the
 * band-extent fix. `flipReason` is deliberately not asserted here: it is a field the fix added, and
 * this file has to keep compiling against the pre-fix engine so its bite can be measured.
 */
class RealisticOrientationContractTest {

    private companion object {
        /** A page photographed squarely in the middle of the frame, on a dark surface. */
        val PAGE_QUAD = Quad(
            Pt(300.0, 250.0),
            Pt(900.0, 250.0),
            Pt(900.0, 1350.0),
            Pt(300.0, 1350.0),
        )

        /** Every fixture that is prose with something on the page, upright and turned over. */
        fun proseFixtures(): List<Pair<String, GrayImage>> = listOf(
            "plain prose" to RealisticPages.prosePage(lines = 20),
            "uneven light" to RealisticPages.prosePageUnderUnevenLight(),
            "lower-half shadow" to RealisticPages.prosePageShadowedLowHalf(),
            "prose with rules" to RealisticPages.proseWithRules(),
            "prose with underlines" to RealisticPages.proseWithUnderlines(),
            "prose with stamp" to RealisticPages.proseWithStamp(),
            "prose with signature" to RealisticPages.proseWithSignature(),
        )
    }

    private fun plan(page: GrayImage): AutoCrop.Plan =
        AutoCrop.plan(RgbaImage.fromGray(page), detectOutline = false)

    private fun assertUprightPageIsNotFlipped(what: String, page: GrayImage) {
        val plan = plan(page)
        assertEquals(
            "$what: an upright page must not be flipped (flipDecided=${plan.flipDecided}, " +
                "deskew=${plan.deskewDegrees})",
            0,
            plan.rotationDegrees,
        )
    }

    /**
     * Contract 2 at full strength, for the fixtures the cue really can deliver.
     *
     * These are the assertions the band-extent fix earned, and they are the ones that must keep
     * failing against the pre-fix engine: they demand the 180-degree turn itself, not the fallback.
     * Confirmed by restoring `Orientation.kt` and `AutoCrop.kt` from `39f8eba`: all five of its
     * callers fail there, each with "expected:<180> but was:<0>", and pass on the fix.
     */
    private fun assertUpsideDownPageIsCorrected(what: String, page: GrayImage) {
        val plan = plan(RealisticPages.turnOver(page))
        assertEquals(
            "$what: an upside-down page must be turned back (flipDecided=${plan.flipDecided}, " +
                "deskew=${plan.deskewDegrees})",
            180,
            plan.rotationDegrees,
        )
    }

    /**
     * Contract 1, as the product can actually keep it.
     *
     * The union is the whole point: the page must be turned back upright, *or* the pass must say it
     * could not tell. The second branch is then asserted rather than assumed — an undecided page
     * has to report `flipDecided = false`, because that flag is what the review overlay reads to
     * put the "Upside down? The automatic pass could not tell" chip on the page. If a fixture takes
     * the undecided branch and still reports `flipDecided = true`, the user is told everything is
     * fine about a page that is upside down, and that is the defect.
     *
     * The extra assertion in that branch — the page is left at rotation 0 — is not decoration. The
     * one-tap fix is a 180-degree flip, so it only lands if the pass left the page exactly as it
     * captured it; a declined page that had already been given a quarter turn would be a page the
     * user cannot fix with the gesture offered.
     *
     * **CONTRACT TEST, not regression evidence.** Measured by restoring `Orientation.kt` and
     * `AutoCrop.kt` from `39f8eba` and re-running: all three of this helper's callers pass against
     * the pre-fix engine and against the fix. That is expected and it is the honest reading — the
     * pre-fix engine reported `flipDecided = false` on all seven prose fixtures, so it was never
     * "silently" wrong *at this seam*; what it could not do was turn five of them back. So this
     * helper pins the necessary condition for the product's promise (the engine never claims a
     * decision it did not make), while the sufficient condition — that the flag actually reaches
     * the review as a tappable chip — is AC-8 and is not testable here. The regression evidence for
     * the fix lives in [assertUpsideDownPageIsCorrected] and in
     * `the flip cue decides where it has evidence and reports abstention where it does not`.
     */
    private fun assertUpsideDownPageIsCorrectedOrFlagged(what: String, page: GrayImage) {
        val plan = plan(RealisticPages.turnOver(page))
        val turnedBack = plan.rotationDegrees == 180
        assertTrue(
            "$what: an upside-down page must be turned back upright, or reported as undecided so " +
                "the user can flip it in one tap — never left upside down with no flag " +
                "(rotationDegrees=${plan.rotationDegrees}, flipDecided=${plan.flipDecided}, " +
                "deskew=${plan.deskewDegrees})",
            turnedBack || !plan.flipDecided,
        )
        if (!turnedBack) {
            assertFalse(
                "$what: the page is left as captured, so the undecided case must be reachable and " +
                    "flagged rather than silently wrong (rotationDegrees=${plan.rotationDegrees})",
                plan.flipDecided,
            )
            assertEquals(
                "$what: the offered fix is a 180-degree flip, so an undecided page must be left " +
                    "exactly as captured for that flip to land " +
                    "(flipDecided=${plan.flipDecided}, deskew=${plan.deskewDegrees})",
                0,
                plan.rotationDegrees,
            )
        }
    }

    // ---------------------------------------------------------------- contract 1 and 2: prose

    @Test
    fun `an upright page of real prose is not flipped`() {
        assertUprightPageIsNotFlipped("plain prose", RealisticPages.prosePage(lines = 20))
    }

    // Renamed from "an upside-down page of real prose is turned back upright": the cue does not
    // decide this page at the shipped 520 px edge, and cannot be made to at any affordable edge,
    // so the old name claimed something untrue. This is the contract that is true of it.
    // CONTRACT TEST: passes against `39f8eba` too — see the helper's note.
    @Test
    fun `an upside-down page of real prose is never left upside down silently`() {
        assertUpsideDownPageIsCorrectedOrFlagged("plain prose", RealisticPages.prosePage(lines = 20))
    }

    @Test
    fun `an upright page of real prose under uneven light is not flipped`() {
        assertUprightPageIsNotFlipped("uneven light", RealisticPages.prosePageUnderUnevenLight())
    }

    @Test
    fun `an upside-down page of real prose under uneven light is turned back upright`() {
        assertUpsideDownPageIsCorrected("uneven light", RealisticPages.prosePageUnderUnevenLight())
    }

    @Test
    fun `an upside-down page shadowed across its lower half is turned back upright`() {
        assertUpsideDownPageIsCorrected("lower-half shadow", RealisticPages.prosePageShadowedLowHalf())
    }

    // ---------------------------------------------------------------- contract 1 and 2: other ink

    @Test
    fun `an upside-down page of real prose carrying rules is turned back upright`() {
        assertUpsideDownPageIsCorrected("prose with rules", RealisticPages.proseWithRules())
    }

    // CONTRACT TEST: passes against `39f8eba` too — see the helper's note.
    @Test
    fun `an upside-down page of real prose carrying underlines is never left upside down silently`() {
        assertUpsideDownPageIsCorrectedOrFlagged(
            "prose with underlines",
            RealisticPages.proseWithUnderlines(),
        )
    }

    // CONTRACT TEST: passes against `39f8eba` too — see the helper's note.
    @Test
    fun `an upside-down page of real prose carrying a stamp is never left upside down silently`() {
        assertUpsideDownPageIsCorrectedOrFlagged("prose with stamp", RealisticPages.proseWithStamp())
    }

    @Test
    fun `an upside-down page of real prose carrying a signature is turned back upright`() {
        assertUpsideDownPageIsCorrected("prose with signature", RealisticPages.proseWithSignature())
    }

    // ---------------------------------------------------------------- the decision, and its absence

    /**
     * Replaces "real prose carries enough evidence for the flip cue to decide", which asserted that
     * both orientations of a page of prose are decided. That is simply false of this statistic, on
     * the measurements quoted in the class comment: the cue decides prose at some resolutions and
     * abstains at others, and the same page moves by a factor of 3.8 on a lighting change, so
     * "enough evidence" is not a property this cue has on prose at any affordable working edge.
     *
     * What is true, and what the product rests on, is the implication in both directions:
     *
     *  - where the pass *decides*, it decides correctly in both orientations (the turned-over page
     *    of the same print under the same lamp must be read as upside down);
     *  - where the pass does not, it *reports* the abstention instead of guessing, leaving the page
     *    as captured with `flipDecided = false` so the review can offer the flip.
     *
     * The two counts at the end are the non-vacuity guard, mirroring [AutoCropOrientationTest]'s:
     * a pass that decided nothing would satisfy every assertion in the loop, and a pass that
     * decided everything would satisfy them too, so both branches have to be seen to be taken.
     *
     * **Regression evidence.** Against `39f8eba`'s engine this test fails, and it fails on exactly
     * the guard that matters: the pre-fix cue abstained on all seven prose fixtures, every one of
     * them, so it had nothing to say about any page of prose at all. On the fix it passes.
     */
    @Test
    fun `the flip cue decides where it has evidence and reports abstention where it does not`() {
        val decided = ArrayList<String>()
        val abstained = ArrayList<String>()

        for ((what, page) in proseFixtures()) {
            val upright = plan(page)
            val over = plan(RealisticPages.turnOver(page))

            assertEquals(
                "$what: whatever the flip cue says, an upright page faces the way it was captured",
                0,
                upright.rotationDegrees,
            )

            if (upright.flipDecided) {
                decided.add(what)
                assertEquals(
                    "$what: the cue decided the upright page, so the turned-over page of the same " +
                        "print must be turned back (flipDecided=${over.flipDecided})",
                    180,
                    over.rotationDegrees,
                )
                assertTrue(
                    "$what: a decision on one orientation must be a decision on the other " +
                        "(rotationDegrees=${over.rotationDegrees})",
                    over.flipDecided,
                )
            } else {
                abstained.add(what)
                assertEquals(
                    "$what: no evidence, so the page must be left as captured",
                    0,
                    over.rotationDegrees,
                )
                assertFalse(
                    "$what: no evidence, so the pass must say so rather than silently leave the " +
                        "page upside down (rotationDegrees=${over.rotationDegrees})",
                    over.flipDecided,
                )
            }
        }

        assertTrue(
            "the cue must decide some fixture, or this test measures nothing " +
                "(abstained on $abstained)",
            decided.isNotEmpty(),
        )
        assertTrue(
            "the abstention the review banner exists for has to be reachable from real prose, " +
                "or the banner is dead code (decided on $decided)",
            abstained.isNotEmpty(),
        )

        // The other half of the same promise, and the guard against an engine that abstains on
        // everything: ink with no 0-versus-180 reading at all must be declined rather than guessed
        // at. This is AC-14's requirement, and it is where a decline is mandatory rather than
        // merely permitted.
        for ((what, page) in listOf(
            "a boxed table" to RealisticPages.tablePage(),
            "a solid block" to RealisticPages.solidBlockPage(),
            "a single long rule" to RealisticPages.page().also {
                RealisticPages.rule(it, 60, 840, 600, 4, RealisticPages.INK)
            },
            "a speck of ink" to RealisticPages.page().also {
                RealisticPages.stamp(it, 440, 590, 460, 610, RealisticPages.INK)
            },
        )) {
            assertFalse(
                "$what carries no 0-versus-180 reading, so the cue must decline rather than guess",
                plan(page).flipDecided,
            )
            assertFalse(
                "$what turned over carries no reading either, so it must decline the same way",
                plan(RealisticPages.turnOver(page)).flipDecided,
            )
        }
    }

    // ---------------------------------------------------------------- contract 3: abstention

    @Test
    fun `a page with no dominant line direction is not rotated`() {
        // Twelve short lines in a square block: neither axis wins, and the page must come out the
        // way it went in rather than on a coin toss.
        assertUprightPageIsNotFlipped("square text block", RealisticPages.squareTextBlock())
        assertUprightPageIsNotFlipped("two columns", RealisticPages.twoColumnPage())
        assertUprightPageIsNotFlipped("boxed table", RealisticPages.tablePage())
    }

    // ---------------------------------------------------------------- through the whole path

    /**
     * The same page, lit unevenly, photographed onto a dark surface and handed to the complete
     * shipped pass: line detection, flattening, levelling, orientation, skew. This is the only test
     * here that exercises the detector as well, so it is also the one that would fail if detection
     * rather than orientation regressed.
     */
    @Test
    fun `an upside-down photographed page of real prose is turned back upright`() {
        val page = RealisticPages.prosePageUnderUnevenLight(lines = 20)
        val capture = RealisticPages.photograph(page, 1200, 1600, PAGE_QUAD)

        val upright = AutoCrop.plan(capture)
        assertEquals(
            "an upright photographed page must not be flipped (flipDecided=${upright.flipDecided})",
            0,
            upright.rotationDegrees,
        )
        val over = AutoCrop.plan(Transforms.rotate180(capture))
        assertEquals(
            "an upside-down photographed page must be turned back (flipDecided=${over.flipDecided})",
            180,
            over.rotationDegrees,
        )
    }

    @Test
    fun `a page carrying only a solid block decides nothing`() {
        val plan = plan(RealisticPages.solidBlockPage())
        // There is no print to read here at all, so the flip must not claim to have decided. Its
        // quarter turn is not asserted: a square block is unchanged by one, so that reading is not a
        // defect on this fixture. Recorded in the report.
        assertFalse("a solid block offers no evidence to decide a flip", plan.flipDecided)
    }

    // Renamed from "a brief note offering no evidence decides nothing": measured on the fixed
    // engine, this fixture *is* decided — flipReason KEPT upright and FLIPPED turned over, i.e. the
    // note is turned back — so the old name was false of the engine while the body happily passed.
    // The name now states the invariant the body actually asserts. CONTRACT TEST: it also passes
    // against `39f8eba`, so it is not regression evidence for the band-extent fix.
    @Test
    fun `a brief note is decided correctly or left as captured, never turned wrongly`() {
        // Four lines of print on an otherwise blank sheet is the sparse case. The engine currently
        // decides it, and correctly, in both orientations; but it is weak enough that a re-tune may
        // legitimately decline it, so the assertion is the invariant the cue must keep either way:
        // decide, and decide correctly, or decline and change nothing.
        val page = RealisticPages.sparseTextBlock()
        val upright = plan(page)
        val over = plan(RealisticPages.turnOver(page))
        if (upright.flipDecided) {
            assertEquals("decided, so the page must stay upright", 0, upright.rotationDegrees)
        } else {
            assertEquals("declined, so the page must be left as captured", 0, upright.rotationDegrees)
        }
        if (over.flipDecided) {
            assertEquals("decided, so the upside-down page must be turned back", 180, over.rotationDegrees)
        } else {
            assertEquals("declined, so the page must be left as captured", 0, over.rotationDegrees)
        }
    }
}
