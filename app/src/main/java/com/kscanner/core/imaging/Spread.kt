package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Double-page spread handling for book scanning.
 *
 * A spread photographed from above is two pages joined at the gutter. Rather than asking
 * the user to crop each half, the whole spread is outlined once and then cut along the
 * gutter, so both pages come out of a single capture.
 */
object Spread {

    /** The two halves of a spread, plus where the cut landed (a fraction of the width). */
    data class Split(val left: Quad, val right: Quad, val seam: Double)

    /**
     * Cuts [quad] into two pages along a roughly vertical seam.
     *
     * The seam runs between the top and bottom edges, so both halves keep the spread's
     * perspective: each page is still corrected by its own homography afterwards.
     */
    fun split(quad: Quad, seam: Double = 0.5): Split {
        val f = seam.coerceIn(0.2, 0.8)
        val top = Pt.lerp(quad.tl, quad.tr, f)
        val bottom = Pt.lerp(quad.bl, quad.br, f)
        return Split(
            left = Quad(quad.tl, top, bottom, quad.bl),
            right = Quad(top, quad.tr, quad.br, bottom),
            seam = f,
        )
    }

    /**
     * Locates the gutter of a flattened spread: the darkest vertical band near the middle.
     * Returns the seam as a fraction of the width, always within 0.30..0.70.
     */
    fun findSeam(warped: GrayImage): Double {
        val w = warped.width
        val h = warped.height
        if (w < 32 || h < 32) return 0.5

        val columns = DoubleArray(w)
        for (x in 0 until w) {
            var sum = 0L
            for (y in 0 until h) sum += warped.get(x, y)
            columns[x] = sum.toDouble() / h
        }

        val lo = (w * 0.30).roundToInt().coerceAtLeast(1)
        val hi = (w * 0.70).roundToInt().coerceAtMost(w - 2)
        if (hi <= lo) return 0.5

        // A small horizontal average stops one noisy column from claiming the seam.
        val window = max(2, w / 60)
        var bestX = lo
        var bestScore = Double.MAX_VALUE
        for (x in lo..hi) {
            var acc = 0.0
            var n = 0
            for (d in -window..window) {
                val xi = x + d
                if (xi < 0 || xi >= w) continue
                acc += columns[xi]
                n++
            }
            val mean = acc / n
            // Pull the answer toward the centre: a dark page margin is not a gutter.
            val centrality = 1.0 - abs(x - w / 2.0) / (w / 2.0)
            val score = mean - 26.0 * centrality
            if (score < bestScore) {
                bestScore = score
                bestX = x
            }
        }
        return bestX.toDouble() / w
    }

    /**
     * True when the picture really looks like a spread: the gutter band is measurably
     * darker than the paper on either side of it.
     */
    fun looksLikeSpread(warped: GrayImage): Boolean {
        val w = warped.width
        if (w < 32) return false
        val seam = findSeam(warped)
        val seamX = (seam * w).roundToInt().coerceIn(0, w - 1)
        val centre = columnMean(warped, seamX)
        val sides = (columnMean(warped, (w * 0.12).roundToInt()) +
            columnMean(warped, (w * 0.88).roundToInt())) / 2.0
        return sides - centre > 16.0
    }

    private fun columnMean(image: GrayImage, x: Int): Double {
        val xi = x.coerceIn(0, image.width - 1)
        var sum = 0L
        for (y in 0 until image.height) sum += image.get(xi, y)
        return sum.toDouble() / image.height
    }
}
