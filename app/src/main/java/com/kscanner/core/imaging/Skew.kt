package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Skew (rotation) estimation.
 *
 * A page photographed by hand is almost never level. Text set on a baseline produces a
 * projection profile with sharp peaks and valleys when the projection direction matches the
 * text rows, and a smeared one when it does not. Squaring and summing the profile turns
 * that into a single number to maximise over candidate angles.
 *
 * For small angles the projection of a pixel onto the rotated y axis is
 * `y - x*tan(theta)`, so every candidate angle costs one pass over the ink mask rather than
 * a full resample. The winner is then refined on a finer grid.
 */
object Skew {

    /** Positive angles are clockwise in image coordinates (y grows downward). */
    data class Estimate(
        val degrees: Double,
        val confidence: Double,
        /** Profile sharpness at the winning angle, relative to the level-page baseline. */
        val gain: Double,
    )

    const val DEFAULT_MAX_ANGLE = 8.0

    fun estimate(
        gray: GrayImage,
        maxAngle: Double = DEFAULT_MAX_ANGLE,
        workingEdge: Int = 560,
    ): Estimate {
        val working = downscale(gray, workingEdge)
        val ink = Segmentation.inkMask(
            working,
            radius = max(6, min(working.width, working.height) / 22),
            bias = 10,
        )
        return estimateInk(ink, maxAngle)
    }

    /**
     * @param ink a mask where 255 marks ink; typically from [Segmentation.inkMask].
     *   Deliberately a distinct name: when both overloads took a bare [GrayImage] the
     *   one-argument call silently bound to this one, treating a photograph as a mask.
     */
    fun estimateInk(ink: GrayImage, maxAngle: Double = DEFAULT_MAX_ANGLE): Estimate {
        if (countInk(ink) < 250) return Estimate(0.0, 0.0, 1.0)

        // One bin layout for the whole search keeps the scores comparable between angles;
        // deriving the range from each candidate angle would reward the smaller layouts.
        val shift = abs(tan(maxAngle * Math.PI / 180.0)) * ink.width

        val coarse = scan(ink, -maxAngle, maxAngle, 1.0, shift)
        val fine = scan(ink, coarse.degrees - 1.0, coarse.degrees + 1.0, 0.1, shift)

        val baseline = scoreAt(ink, 0.0, shift)
        val gain = if (baseline <= 0.0) 1.0 else fine.score / baseline

        // A page already level scores 1.0 against itself, so the gain only becomes evidence
        // when the winner is meaningfully better than doing nothing at all.
        val confidence = ((gain - 1.0) / 0.45).coerceIn(0.0, 1.0)
        val degrees = if (gain < 1.03) 0.0 else fine.degrees
        return Estimate(degrees, confidence, gain)
    }

    private data class Sample(val degrees: Double, val score: Double)

    private fun scan(ink: GrayImage, from: Double, to: Double, step: Double, shift: Double): Sample {
        var best = Sample(0.0, -1.0)
        var angle = from
        while (angle <= to + 1e-9) {
            val score = scoreAt(ink, angle, shift)
            if (score > best.score) best = Sample(angle, score)
            angle += step
        }
        return best
    }

    /**
     * Profile sharpness of the ink projected onto an axis rotated by [degrees], using a bin
     * layout of fixed width determined by [shift].
     */
    private fun scoreAt(ink: GrayImage, degrees: Double, shift: Double): Double {
        val w = ink.width
        val h = ink.height
        val slope = tan(degrees * Math.PI / 180.0)
        val bins = h + (2 * shift).roundToInt() + 3
        val offset = shift.roundToInt() + 1

        val profile = IntArray(bins)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (ink.data[row + x] < 128) continue
                val bin = (y - x * slope).roundToInt() + offset
                if (bin in 0 until bins) profile[bin]++
            }
        }
        return Segmentation.profileSharpness(profile)
    }

    private fun countInk(ink: GrayImage): Int {
        var count = 0
        for (v in ink.data) if (v >= 128) count++
        return count
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
