package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    )

    val UPRIGHT: Estimate = Estimate(0, 0.0, 0.0, 0.0)

    /** Margin a flip must clear when three or more bands voted. */
    private const val FLIP_MARGIN = 0.02

    /**
     * Margin when only two bands voted. Two centroids average less of the noise that makes
     * a lone band a coin toss, but they are still thin evidence beside a page of prose, so
     * the same threshold would let a stray mark turn the page over; demanding more
     * displacement keeps the extra sensitivity honest.
     */
    private const val SPARSE_FLIP_MARGIN = 0.03

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
        // A sparse page raises two bands' word to the same standing as a page of prose, so
        // it has to clear a wider margin to be believed. The weaker of the two readings
        // sets the margin, because the pair's profiles are mirror images and the count that
        // proves least is the one that should govern.
        val evidenceBands = min(pair[0].bias.bands, pair[1].bias.bands)
        val flipMargin = if (evidenceBands >= 3) FLIP_MARGIN else SPARSE_FLIP_MARGIN
        val flipGap = pair[1].bias.value - pair[0].bias.value
        val flipDecided = abs(flipGap) > flipMargin
        val chosen = if (flipGap > flipMargin) pair[1] else pair[0]
        val runnerUpScore = if (useVertical) horizontalScore else verticalScore
        val axisMargin = if (chosen.score <= 0.0) 0.0 else {
            ((chosen.score - runnerUpScore) / chosen.score).coerceIn(0.0, 1.0)
        }

        val flipConfidence = (abs(flipGap) / 0.10).coerceIn(0.0, 1.0)

        val confidence = (axisMargin * 0.7 + flipConfidence * 0.3).coerceIn(0.0, 1.0)
        return Estimate(chosen.rotation, confidence, chosen.score, chosen.bias.value, flipDecided)
    }

    private data class Candidate(val rotation: Int, val score: Double, val bias: Bias)

    /**
     * How far a frame's ink sits below the centre of its text bands, in units of band
     * height, and how many bands carried that evidence.
     *
     * The count is what tells a page of prose apart from a page with a couple of lines on
     * it, and the caller needs it to pick a margin it can defend; a value of [Bias.NONE]
     * means the frame offered no evidence at all.
     */
    private class Bias(val value: Double, val bands: Int) {
        companion object {
            val NONE = Bias(0.0, 0)
        }
    }

    /**
     * Mean displacement of each text band's ink centroid from the band centre, in units of
     * band height. Only bands that agree with the majority are counted, so a stray artefact
     * cannot outvote the text.
     *
     * @param minInk rejected unless the bands that voted carry at least this much ink.
     */
    private fun flipBias(profile: IntArray, minInk: Double): Bias {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return Bias.NONE

        // Relax the cut until there are enough bands for a majority to mean something. It
        // never drops below a tenth of the peak: past that the gaps between lines fill in
        // and the "bands" are no longer lines of text.
        var cutFraction = 0.30
        var bands = bands(profile, cutFraction)
        while (bands.size < 3 && cutFraction > 0.10) {
            cutFraction -= 0.05
            bands = bands(profile, cutFraction)
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
        for (b in biases) {
            sum += b
            if (b > 0.01) positive++
            if (b < -0.01) negative++
        }
        val agreeing = max(positive, negative)
        if (agreeing < MIN_AGREEING_BANDS || agreeing * 2 < biases.size) return Bias.NONE
        return Bias(sum / biases.size, agreeing)
    }

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
