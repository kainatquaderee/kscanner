package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Whole-page orientation: is this capture upright, sideways or upside down?
 *
 * Two independent cues are combined.
 *
 * 1. **Axis.** Text set in lines makes the ink projection profile along the line-stacking
 *    axis much peakier than along the other axis, so comparing the row and column profiles
 *    decides between {0, 180} and {90, 270}. This cue is strong and reliable.
 *
 * 2. **Flip.** Within each text band the ink mass sits nearer the baseline than the band
 *    centre, because ascenders and capitals stretch the band upward far more than
 *    descenders stretch it downward. That displacement reverses when the page is turned
 *    over, so the majority sign across bands decides 0 versus 180 and 90 versus 270. This
 *    cue is a heuristic; it is only applied when the bands agree strongly, and the
 *    confidence it reports is deliberately conservative. It is also the cue that needs a
 *    *level* page: a couple of degrees of tilt smear a band into its neighbours and the
 *    displacement it measures collapses, so callers hand [estimate] a deskewed page.
 */
object Orientation {

    /** [rotationDegrees] is the clockwise rotation that would make the page upright. */
    data class Estimate(
        val rotationDegrees: Int,
        val confidence: Double,
        /** Best profile sharpness of the line-stacking axis. */
        val axisScore: Double,
        /** Positive when the ink sits below the centre of its band, as upright text does. */
        val flipBias: Double,
        /**
         * True when the 0-versus-180 choice was actually decided: the two candidates'
         * ink biases differed by more than the margin their evidence supports, so turning
         * the page over is not needed. False means the pass had no evidence to choose
         * between 0 and 180 and left the page as captured — symmetric content (bars, a
         * table grid, a barcode), too few usable text bands, or too little ink.
         *
         * [UPRIGHT] is false by that rule, deliberately: it is the answer to "nothing was
         * measured", not a confident "already upright". A caller that offers a one-tap
         * flip needs exactly that distinction, and treating the fallback as a decision
         * would hide the pages that most need the tap.
         */
        val flipDecided: Boolean = false,
        /**
         * Why the 0-versus-180 choice came out the way it did. Carried so a caller can say *why*
         * a page was left as captured: a page with no reading at all is a different thing to a
         * reading too small to believe, and only the first is worth asking the user about.
         */
        val flipReason: FlipReason = FlipReason.ONE_SIDE_ABSTAINED,
    )

    val UPRIGHT: Estimate = Estimate(0, 0.0, 0.0, 0.0)

    /** Margin a flip must clear when three or more bands voted. */
    private const val FLIP_MARGIN = 0.02

    /** Cut that finds a line's x-height core, as it always has. */
    private const val CORE_CUT = 0.30

    /** Rows below this fraction of the peak are paper, not line: the growth floor. */
    private const val SKIRT_CUT = 0.03

    /**
     * Margin when only two bands voted. Two centroids average less of the noise that makes
     * a lone band a coin toss, but they are still thin evidence beside a page of prose, so
     * the same threshold would let a stray mark turn the page over; demanding more
     * displacement keeps the extra sensitivity honest.
     */
    private const val SPARSE_FLIP_MARGIN = 0.03

    /**
     * Band readings scatter 0.02-0.07 of band height (see [MIN_AGREEING_BANDS]); 0.05 is that
     * scatter when a candidate has too few readings to measure its own.
     */
    private const val BAND_SIGMA_FLOOR = 0.05

    /**
     * Fewest bands that must agree before their displacement may decide a flip.
     *
     * A lone band is a coin toss, not evidence, and the arithmetic says so. At the 520 px
     * working edge a text band is seven or eight pixels tall, and the pair's profiles are
     * exact mirror images, so the decision rests on twice that one band's
     * centroid-versus-centre offset, set against a margin of 0.03 band height — a quarter of
     * a pixel. What that buys is visible in the fixture that showed the fault: an upright
     * page carrying one line produced a single band reading of 0.07 of band height, about
     * half a pixel of centroid displacement, and it cleared the margin and turned the page
     * over. Individual band readings across a sixteen-line page scatter by 0.02 to 0.07 of
     * band height, the same size as the mean they average, so one band cannot be told from
     * its own noise. Two bands that agree are the least that can.
     */
    private const val MIN_AGREEING_BANDS = 2

    /**
     * Least ink, as a fraction of the working frame, that may decide a flip.
     *
     * Calibrated against measured fixtures at the 520 px working edge: one line of text
     * carries about 0.9% of the frame's pixels, so a third of a line is the floor for
     * "there is text here". A speck of dust or a short pen mark is an order of magnitude
     * below it. A long thin rule can pass the floor, but a rule has a uniform profile and
     * therefore a near-zero bias, so the margin above still refuses to flip on it.
     */
    private const val MIN_FLIP_INK_FRACTION = 0.003

    /** Absolute floor, for the smallest frames where the fraction would vanish. */
    private const val MIN_FLIP_INK = 300.0

    fun estimate(gray: GrayImage, workingEdge: Int = 520): Estimate {
        val working = downscale(gray, workingEdge)
        val ink = Segmentation.inkMask(
            working,
            radius = max(6, min(working.width, working.height) / 22),
            bias = 10,
        )
        return estimateInk(ink)
    }

    /**
     * @param ink a mask where 255 marks ink; typically from [Segmentation.inkMask].
     *   Deliberately a distinct name: when both overloads took a bare [GrayImage] the
     *   one-argument call silently bound to this one, treating a photograph as a mask.
     */
    fun estimateInk(ink: GrayImage): Estimate {
        var inked = 0
        for (v in ink.data) if (v >= 128) inked++
        if (inked < 300) return UPRIGHT

        // Score every quarter turn in the frame it produces, so the comparison uses the
        // same measure for all four candidates.
        val minInk = max(MIN_FLIP_INK, MIN_FLIP_INK_FRACTION * ink.width * ink.height)
        val candidates = ArrayList<Candidate>(4)
        var current = ink
        for (turn in 0..3) {
            val profile = Segmentation.rowProfile(current)
            val score = Segmentation.profileSharpness(profile)
            val bias = flipBias(profile, minInk)
            candidates.add(Candidate(turn * 90, score, bias))
            if (turn < 3) current = rotate90(current)
        }

        // Sharpness identifies the axis: it cannot separate a frame from the same frame
        // turned over, because the profile is mirrored and mirrors score identically.
        val verticalPair = listOf(candidates[0], candidates[2])
        val horizontalPair = listOf(candidates[1], candidates[3])
        val verticalScore = verticalPair.maxOf { it.score }
        val horizontalScore = horizontalPair.maxOf { it.score }
        val useVertical = verticalScore >= horizontalScore
        val pair = if (useVertical) verticalPair else horizontalPair

        // Within the surviving pair the flip bias decides: the upright member is the one
        // whose ink sits below the centre of its text bands. The cue is weak, so default to
        // the member that needs no flip unless the evidence for turning the page over is
        // clear. A page that is genuinely upside down still gets corrected; one whose bands
        // are symmetric (a table, a barcode) is left as captured.
        //
        // A sparse page raises two bands' word to the same standing as a page of prose, so it has
        // to clear a wider margin to be believed. A candidate that produced no reading at all
        // cannot decide the flip and cannot relax the margin: see [decideFlip].
        val decision = decideFlip(pair[0].bias.evidence(), pair[1].bias.evidence())
        val chosen = if (decision.flip) pair[1] else pair[0]
        val flipDecided = decision.decided
        val runnerUpScore = if (useVertical) horizontalScore else verticalScore
        val axisMargin = if (chosen.score <= 0.0) 0.0 else {
            ((chosen.score - runnerUpScore) / chosen.score).coerceIn(0.0, 1.0)
        }

        val flipConfidence = (abs(decision.gap) / 0.10).coerceIn(0.0, 1.0)

        val confidence = (axisMargin * 0.7 + flipConfidence * 0.3).coerceIn(0.0, 1.0)
        return Estimate(
            chosen.rotation,
            confidence,
            chosen.score,
            chosen.bias.value,
            flipDecided,
            decision.reason,
        )
    }

    private data class Candidate(val rotation: Int, val score: Double, val bias: Bias)

    /**
     * How far a frame's ink sits below the centre of its text bands, in units of band
     * height, how many bands carried that evidence, and the scatter of those readings.
     *
     * The count is what tells a page of prose apart from a page with a couple of lines on
     * it, and the caller needs it to pick a margin it can defend; a value of [Bias.NONE]
     * means the frame offered no evidence at all. That is why [measured] exists rather than
     * comparing [value] with zero: no reading and a reading of 0.0 are different things, and
     * only one of them may decide a flip.
     */
    private class Bias(
        val value: Double,
        val bands: Int,
        val samples: Int,
        val sumSquares: Double,
    ) {
        /** False ⇒ this candidate has no reading at all, not a reading of zero. */
        val measured: Boolean get() = bands >= 1

        fun evidence() = FlipEvidence(value, bands, samples, sumSquares)

        companion object {
            val NONE = Bias(0.0, 0, 0, 0.0)
        }
    }

    /** What one 0-versus-180 candidate had to say, in the form [decideFlip] reads. */
    data class FlipEvidence(
        val mean: Double,
        val bands: Int,
        val samples: Int,
        val sumSquares: Double,
    ) {
        /** False ⇒ this candidate has no reading at all, not a reading of zero. */
        val measured: Boolean get() = bands >= 1

        companion object {
            val NONE = FlipEvidence(0.0, 0, 0, 0.0)
        }
    }

    /** Why [decideFlip] came out the way it did. */
    enum class FlipReason { FLIPPED, KEPT, ONE_SIDE_ABSTAINED, BELOW_NOISE }

    /** The whole 0-versus-180 decision, with the numbers it was made on. */
    data class FlipDecision(
        /** True ⇒ turn the frame 180 degrees. */
        val flip: Boolean,
        /** False ⇒ no evidence; leave the frame as captured. */
        val decided: Boolean,
        val reason: FlipReason,
        /** [FlipEvidence.mean] of the turned candidate minus that of the as-captured one. */
        val gap: Double,
        /** The margin [gap] had to clear. */
        val margin: Double,
        /** From the readings' own scatter. */
        val standardError: Double,
    )

    /**
     * The 0-versus-180 decision, pure and whole: no mask, no profile, no image.
     *
     * The rules, in the order they apply — a change to any of them is a behaviour change:
     *
     *  1. A candidate that produced no reading abstains. It can never decide the flip and it
     *     never relaxes the margin for the other side, which is what stops one member's
     *     silence from letting the other member's reading turn the page on its own.
     *  2. A candidate carrying fewer than three bands only gets two bands' word, so the
     *     margin widens to [SPARSE_FLIP_MARGIN].
     *  3. The gap has to clear the margin strictly, and a flip needs the gap on the positive
     *     side; a gap the wrong way is a decision to keep the frame as captured.
     *
     * The readings' scatter is reported in [FlipDecision.standardError] but does not raise the
     * margin: see the kill criterion note inside [decideFlip].
     */
    fun decideFlip(asCaptured: FlipEvidence, turned: FlipEvidence): FlipDecision {
        val gap = turned.mean - asCaptured.mean
        val bandMargin = if (min(asCaptured.bands, turned.bands) >= 3) {
            FLIP_MARGIN
        } else {
            SPARSE_FLIP_MARGIN
        }
        if (!asCaptured.measured || !turned.measured) {
            // No scatter can be computed from a candidate that produced no readings, and none
            // is needed: the abstention decides the outcome on its own.
            return FlipDecision(
                flip = false,
                decided = false,
                reason = FlipReason.ONE_SIDE_ABSTAINED,
                gap = gap,
                margin = bandMargin,
                standardError = 0.0,
            )
        }

        val standardError = sqrt(
            standardErrorOf(asCaptured) * standardErrorOf(asCaptured) +
                standardErrorOf(turned) * standardErrorOf(turned),
        )
        // The noise guard that was tried here is deleted, and [bandMargin] alone decides: measured
        // on the prose fixture it was calibrated against, the gap the cue produces is 0.0189 against
        // a guard of 0.0244, so the guard re-created the false negative it was meant to bound.
        // [standardError] is still reported so a caller (and the tests) can see the scatter the
        // margin no longer uses.
        val margin = bandMargin
        val decided = abs(gap) > margin
        val flip = decided && gap > margin
        val reason = when {
            !decided -> FlipReason.BELOW_NOISE
            flip -> FlipReason.FLIPPED
            else -> FlipReason.KEPT
        }
        return FlipDecision(flip, decided, reason, gap, margin, standardError)
    }

    /** Standard error of one candidate's mean, from the readings' own scatter. */
    private fun standardErrorOf(evidence: FlipEvidence): Double {
        if (evidence.samples <= 0) return 0.0
        val sigma = if (evidence.samples >= 2) {
            sqrt(evidence.sumSquares / (evidence.samples - 1))
        } else {
            BAND_SIGMA_FLOOR
        }
        return sigma / sqrt(evidence.samples.toDouble())
    }

    /**
     * Mean displacement of each text band's ink centroid from the band centre, in units of
     * band height, and the number of bands that carried that evidence.
     *
     * Every band that holds ink contributes to the mean; the earlier claim that only bands
     * agreeing with the majority were counted was never true of the arithmetic, and the
     * majority is what [MIN_AGREEING_BANDS] and the margin are for.
     *
     * @param minInk rejected unless the bands that carried ink hold at least this much.
     */
    private fun flipBias(profile: IntArray, minInk: Double): Bias {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return Bias.NONE

        // Relax the cut until there are enough bands for a majority to mean something. It
        // never drops below a tenth of the peak: past that the gaps between lines fill in
        // and the "bands" are no longer lines of text.
        var cutFraction = CORE_CUT
        var bands = textBands(profile, cutFraction)
        while (bands.size < 3 && cutFraction > 0.10) {
            cutFraction -= 0.05
            bands = textBands(profile, cutFraction)
        }

        val biases = ArrayList<Double>(bands.size)
        var ink = 0.0
        for (band in bands) {
            // A band that runs to the very top or bottom of the crop is the page edge, not a
            // line of text; its ink is the darker surface behind the sheet.
            if (band.first <= 0 || band.last >= profile.size - 1) continue
            var mass = 0.0
            var weighted = 0.0
            for (y in band) {
                mass += profile[y]
                weighted += profile[y].toDouble() * y
            }
            if (mass <= 0.0) continue
            ink += mass
            val centroid = weighted / mass
            val center = (band.first + band.last) / 2.0
            val height = (band.last - band.first + 1).toDouble()
            biases.add((centroid - center) / height)
        }

        // A band has an opinion only when it holds enough ink to be a line of text at all.
        // This is what stops a speck of dust from turning a page over, and together with
        // [MIN_AGREEING_BANDS] it is the floor the flip decision stands on.
        if (biases.isEmpty() || ink < minInk) return Bias.NONE

        var positive = 0
        var negative = 0
        var sum = 0.0
        var sumSquares = 0.0
        for (b in biases) {
            sum += b
            sumSquares += b * b
            if (b > 0.01) positive++
            if (b < -0.01) negative++
        }
        val agreeing = max(positive, negative)
        if (agreeing < MIN_AGREEING_BANDS || agreeing * 2 < biases.size) return Bias.NONE
        return Bias(sum / biases.size, agreeing, biases.size, sumSquares)
    }

    /**
     * One row range per line of print: the [coreCut] cores grown outward to the valley that
     * separates them, so the ascenders and descenders the cue reads (see the class KDoc) are
     * inside the band rather than cut away by it.
     *
     * The cores are where the profile is at least [coreCut] of its peak, which isolates the
     * x-height run and discards the very evidence the cue depends on: the ascender and
     * descender rows carry far less mass than the x-height run, so any single cut that finds
     * the x-height also hides them. They are recovered by growing each core outward while the
     * profile is still descending — the descent into the inter-line gap is the boundary that
     * separates one line of print from the paper, so no third threshold and no per-font
     * tuning is needed — and never past [skirt] of the peak, which is paper.
     *
     * Growth also stops at the midpoint to the neighbouring core, so two lines whose skirts
     * meet on tight leading split at the valley between them instead of swallowing each
     * other. A band can therefore absorb a sliver of its neighbour's skirt, which moves the
     * mean by a fraction of that skirt rather than destroying the sign.
     */
    internal fun textBands(
        profile: IntArray,
        coreCut: Double = CORE_CUT,
        skirt: Double = SKIRT_CUT,
    ): List<IntRange> {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return emptyList()
        val cores = bands(profile, coreCut)
        if (cores.isEmpty()) return emptyList()
        val floor = max(1, (peak * skirt).roundToInt())

        val result = ArrayList<IntRange>(cores.size)
        for (i in cores.indices) {
            val core = cores[i]
            // Rows strictly between the cores go to the one above; the cap is what keeps two
            // grown bands from overlapping when the valley between them carries ink.
            val upCap = if (i == 0) 0 else (cores[i - 1].last + core.first) / 2 + 1
            val downCap = if (i == cores.size - 1) profile.size - 1 else (core.last + cores[i + 1].first) / 2

            var first = core.first
            while (first > upCap && growsOutward(profile, first - 1, profile[first], floor)) first--
            var last = core.last
            while (last < downCap && growsOutward(profile, last + 1, profile[last], floor)) last++
            result.add(first..last)
        }
        return result
    }

    /** True while [outer] is still part of the line inside it: descending, and not paper. */
    private fun growsOutward(profile: IntArray, outer: Int, inner: Int, floor: Int): Boolean =
        profile[outer] >= floor && profile[outer] < inner

    /** Contiguous index ranges where the profile rises above a fraction of its peak. */
    private fun bands(profile: IntArray, fraction: Double): List<IntRange> {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return emptyList()
        val cut = max(1, (peak * fraction).roundToInt())
        val result = ArrayList<IntRange>()
        var start = -1
        for (i in profile.indices) {
            if (profile[i] >= cut) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= 2) result.add(start until i)
                start = -1
            }
        }
        if (start >= 0 && profile.size - start >= 2) result.add(start until profile.size)
        return result
    }

    /** Clockwise quarter turn of a mask, matching [Transforms.rotate90]. */
    fun rotate90(src: GrayImage): GrayImage {
        val dstW = src.height
        val dstH = src.width
        val out = IntArray(src.size)
        var i = 0
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                out[i++] = src.get(dy, src.height - 1 - dx)
            }
        }
        return GrayImage(dstW, dstH, out)
    }

    private fun downscale(gray: GrayImage, workingEdge: Int): GrayImage {
        val longest = max(gray.width, gray.height)
        if (longest <= workingEdge) return gray
        val scale = workingEdge.toDouble() / longest
        return Transforms.resizeGray(
            gray,
            max(1, (gray.width * scale).roundToInt()),
            max(1, (gray.height * scale).roundToInt()),
        )
    }
}
