package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Angular difference between two line directions, folded into 0..90 degrees.
 *
 * A line running at 5 degrees and one running at 175 degrees are the same family; naive
 * subtraction would call them opposites.
 */
internal fun directionDelta(a: Double, b: Double): Double {
    val raw = abs(a - b) % 180.0
    return if (raw > 90.0) 180.0 - raw else raw
}

/**
 * Line evidence for one working scale.
 *
 * The expensive stages live here so they run once per frame rather than once per candidate:
 * Canny, the Hough transform, the dominant direction, and the split into two orthogonal
 * line families.
 *
 * The families are built around the *dominant* direction of the strong lines rather than
 * around the image axes. That is the single change that makes a page photographed at 25
 * degrees as easy to find as a level one: a page tilted in the frame has no horizontal or
 * vertical edges at all, so axis-binned families see nothing.
 */
internal class DocLines(
    val width: Int,
    val height: Int,
    val edgeMask: GrayImage,
    val magnitude: FloatArray,
    val maxMagnitude: Float,
    val lines: List<Hough.Line>,
    /** Lines running along the dominant direction. */
    val familyA: List<Hough.Line>,
    /** Lines running across the dominant direction. */
    val familyB: List<Hough.Line>,
    val dominantDegrees: Double,
) {

    /** Gradient level that counts as an edge for support tests. */
    val gradientThreshold: Float get() = max(14f, maxMagnitude * 0.15f)

    fun magnitudeAt(x: Double, y: Double): Float {
        val xi = x.roundToInt().coerceIn(0, width - 1)
        val yi = y.roundToInt().coerceIn(0, height - 1)
        return magnitude[yi * width + xi]
    }

    /**
     * Fraction of sample points along a quad's perimeter that sit on a gradient ridge.
     *
     * This is the measure that separates a real page boundary from a plausible-looking
     * rectangle drawn through blank paper: interior rules and table lines are edges, but
     * the space *between* them is not, so an outline traced through them scores poorly.
     */
    fun perimeterSupport(quad: Quad, stepsPerSide: Int = 40): Double {
        val corners = quad.points
        val threshold = gradientThreshold
        var hits = 0
        var samples = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            for (s in 0 until stepsPerSide) {
                val t = (s + 0.5) / stepsPerSide
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                samples++
                var found = false
                search@ for (dy in -2..2) {
                    for (dx in -2..2) {
                        val xi = (x + dx).roundToInt()
                        val yi = (y + dy).roundToInt()
                        if (xi < 0 || yi < 0 || xi >= width || yi >= height) continue
                        if (magnitude[yi * width + xi] >= threshold) {
                            found = true
                            break@search
                        }
                    }
                }
                if (found) hits++
            }
        }
        return if (samples == 0) 0.0 else hits.toDouble() / samples
    }

    /**
     * Re-fits each side of [quad] to the nearest gradient ridge, then takes the new corners
     * as the intersections of those fitted lines.
     *
     * The Hough accumulator is quantised in both angle and radius, which puts a hard floor
     * of a few pixels on how precisely any candidate can be placed. Snapping to the
     * sub-pixel ridge and re-intersecting removes that floor.
     */
    fun refine(quad: Quad, searchRadius: Int = 5): Quad {
        val corners = quad.points
        val fitted = ArrayList<Pair<Pt, Double>>(4)
        for (i in 0 until 4) {
            val samples = snapSide(corners[i], corners[(i + 1) % 4], searchRadius)
            val line = Segmentation.fitLine(samples) ?: return quad
            fitted.add(line)
        }

        val refined = ArrayList<Pt>(4)
        for (i in 0 until 4) {
            val intersection = Segmentation.intersectLines(fitted[(i + 3) % 4], fitted[i])
                ?: return quad
            if (intersection.x.isNaN() || intersection.y.isNaN()) return quad
            refined.add(intersection)
        }

        val ordered = runCatching { Quad.order(refined) }.getOrNull() ?: return quad
        if (!ordered.isConvex()) return quad
        // A refinement that wanders far from the candidate is a mis-fit, not an improvement.
        if (maxCornerShift(quad, ordered) > max(6.0, searchRadius * 2.0)) return quad
        return ordered
    }

    /** Largest distance between a corner of [a] and its nearest corner of [b]. */
    private fun maxCornerShift(a: Quad, b: Quad): Double {
        var worst = 0.0
        for (p in a.points) {
            var nearest = Double.MAX_VALUE
            for (q in b.points) {
                val d = p.dist(q)
                if (d < nearest) nearest = d
            }
            if (nearest > worst) worst = nearest
        }
        return worst
    }

    /** Sample points along one side, each pulled onto the strongest nearby ridge. */
    private fun snapSide(a: Pt, b: Pt, radius: Int): List<Pt> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length < 8.0) return emptyList()

        val nx = -dy / length
        val ny = dx / length
        val count = (length / 7.0).toInt().coerceIn(8, 56)
        val points = ArrayList<Pt>(count)
        val floor = gradientThreshold * 0.65f

        for (s in 0 until count) {
            // Stay clear of both ends: near a corner the normal search would otherwise
            // latch onto the crossing edge instead of the side being fitted.
            val t = 0.14 + 0.72 * (s + 0.5) / count
            val x = a.x + dx * t
            val y = a.y + dy * t
            var bestOffset = 0.0
            var bestValue = -1f
            for (o in -radius..radius) {
                val v = magnitudeAt(x + nx * o, y + ny * o)
                if (v > bestValue) {
                    bestValue = v
                    bestOffset = o.toDouble()
                }
            }
            if (bestValue >= floor) points.add(Pt(x + nx * bestOffset, y + ny * bestOffset))
        }
        return points
    }

    companion object {

        fun extract(gray: GrayImage, minVotes: Int, maxPerFamily: Int = 9): DocLines {
            val canny = Edges.canny(gray, sigma = 2.0)
            val lines = Hough.detectLines(canny.edges, minVotes, maxLines = 200)

            var peak = 0f
            for (m in canny.magnitude) if (m > peak) peak = m

            val dominant = dominantDirection(lines)
            val familyA = ArrayList<Hough.Line>()
            val familyB = ArrayList<Hough.Line>()
            for (line in lines) {
                if (directionDelta(line.directionDeg, dominant) <= 45.0) {
                    familyA.add(line)
                } else {
                    familyB.add(line)
                }
            }
            familyA.sortByDescending { it.votes }
            familyB.sortByDescending { it.votes }

            return DocLines(
                width = gray.width,
                height = gray.height,
                edgeMask = canny.edges,
                magnitude = canny.magnitude,
                maxMagnitude = peak,
                lines = lines,
                familyA = familyA.take(maxPerFamily),
                familyB = familyB.take(maxPerFamily),
                dominantDegrees = dominant,
            )
        }

        /**
         * Circular mean of the doubled directions, weighted by vote count. Doubling folds
         * 0 and 180 together, which is what a line family needs.
         */
        private fun dominantDirection(lines: List<Hough.Line>): Double {
            if (lines.isEmpty()) return 0.0
            var sumCos = 0.0
            var sumSin = 0.0
            for (line in lines) {
                val rad = 2.0 * line.directionDeg * Math.PI / 180.0
                val weight = min(line.votes, 2000).toDouble()
                sumCos += cos(rad) * weight
                sumSin += sin(rad) * weight
            }
            if (abs(sumCos) < 1e-9 && abs(sumSin) < 1e-9) return 0.0
            val degrees = 0.5 * atan2(sumSin, sumCos) * 180.0 / Math.PI
            return ((degrees % 180.0) + 180.0) % 180.0
        }
    }
}
