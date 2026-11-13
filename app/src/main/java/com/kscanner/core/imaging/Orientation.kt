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
 *    confidence it reports is deliberately conservative.
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
    )

    val UPRIGHT: Estimate = Estimate(0, 0.0, 0.0, 0.0)

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
        val candidates = ArrayList<Candidate>(4)
        var current = ink
        for (turn in 0..3) {
            val profile = Segmentation.rowProfile(current)
            val score = Segmentation.profileSharpness(profile)
            val bias = flipBias(profile)
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
        val flipMargin = 0.02
        val chosen = if (pair[1].bias > pair[0].bias + flipMargin) pair[1] else pair[0]
        val runnerUpScore = if (useVertical) horizontalScore else verticalScore
        val axisMargin = if (chosen.score <= 0.0) 0.0 else {
            ((chosen.score - runnerUpScore) / chosen.score).coerceIn(0.0, 1.0)
        }

        val flipGap = abs(pair[0].bias - pair[1].bias)
        val flipConfidence = (flipGap / 0.10).coerceIn(0.0, 1.0)

        val confidence = (axisMargin * 0.7 + flipConfidence * 0.3).coerceIn(0.0, 1.0)
        return Estimate(chosen.rotation, confidence, chosen.score, chosen.bias)
    }

    private data class Candidate(val rotation: Int, val score: Double, val bias: Double)

    /**
     * Mean displacement of each text band's ink centroid from the band centre, in units of
     * band height. Only bands that agree with the majority are averaged, so a stray
     * artefact cannot outvote the text.
     */
    private fun flipBias(profile: IntArray): Double {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return 0.0

        var cutFraction = 0.30
        var bands = bands(profile, cutFraction)
        while (bands.size < 3 && cutFraction > 0.10) {
            cutFraction -= 0.05
            bands = bands(profile, cutFraction)
        }
        if (bands.size < 3) return 0.0

        val biases = ArrayList<Double>(bands.size)
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
            val centroid = weighted / mass
            val center = (band.first + band.last) / 2.0
            val height = (band.last - band.first + 1).toDouble()
            biases.add((centroid - center) / height)
        }
        if (biases.size < 3) return 0.0

        var positive = 0
        var negative = 0
        var sum = 0.0
        for (b in biases) {
            sum += b
            if (b > 0.01) positive++
            if (b < -0.01) negative++
        }
        val agreeing = max(positive, negative)
        if (agreeing * 2 < biases.size) return 0.0
        return sum / biases.size
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
