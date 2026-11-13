package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Planar projective transform (homography) between two quadrilaterals, and the
 * perspective warp that flattens a photographed page into a rectangle.
 *
 * The 3x3 matrix is recovered by solving the standard 8x8 direct linear system
 * with Gaussian elimination and partial pivoting.
 */
object Homography {

    /**
     * Solves for the 3x3 matrix H (row-major, 9 values, h[8] fixed to 1) such that
     * `H * from[i] == to[i]` for all four correspondences.
     */
    fun solve(from: List<Pt>, to: List<Pt>): DoubleArray {
        require(from.size == 4 && to.size == 4) { "homography needs exactly 4 point pairs" }

        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val fx = from[i].x
            val fy = from[i].y
            val tx = to[i].x
            val ty = to[i].y
            a[2 * i] = doubleArrayOf(fx, fy, 1.0, 0.0, 0.0, 0.0, -tx * fx, -tx * fy)
            b[2 * i] = tx
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, fx, fy, 1.0, -ty * fx, -ty * fy)
            b[2 * i + 1] = ty
        }

        val h = gaussianSolve(a, b)
        return doubleArrayOf(
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1.0,
        )
    }

    /** Applies H to a point. Returns a NaN point when the point maps to infinity. */
    fun map(h: DoubleArray, p: Pt): Pt {
        val d = h[6] * p.x + h[7] * p.y + h[8]
        if (abs(d) < 1e-12) return Pt(Double.NaN, Double.NaN)
        return Pt(
            (h[0] * p.x + h[1] * p.y + h[2]) / d,
            (h[3] * p.x + h[4] * p.y + h[5]) / d,
        )
    }

    /** Output size that preserves the outline's aspect ratio, optionally capped. */
    fun outputSizeFor(quad: Quad, maxEdge: Int? = null): Pair<Int, Int> {
        val (w, h) = quad.unwarpedSize()
        if (maxEdge == null) return Pair(w, h)
        val longest = max(w, h)
        if (longest <= maxEdge) return Pair(w, h)
        val scale = maxEdge.toDouble() / longest
        return Pair(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
    }

    /**
     * Warps the region of [src] described by [quad] into an [outWidth] x [outHeight]
     * rectangle. Sampling is inverse-mapped with bilinear interpolation.
     */
    fun warpPerspective(src: RgbaImage, quad: Quad, outWidth: Int, outHeight: Int): RgbaImage {
        require(outWidth > 0 && outHeight > 0)

        val maxX = (outWidth - 1).toDouble()
        val maxY = (outHeight - 1).toDouble()
        val rect = listOf(Pt(0.0, 0.0), Pt(maxX, 0.0), Pt(maxX, maxY), Pt(0.0, maxY))

        // Maps destination rectangle coordinates back into source image coordinates.
        val inverse = solve(rect, quad.points)

        val out = IntArray(outWidth * outHeight)
        var i = 0
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val p = map(inverse, Pt(x.toDouble(), y.toDouble()))
                out[i++] = if (p.x.isNaN() || p.y.isNaN()) {
                    0xFFFFFFFF.toInt()
                } else {
                    src.sampleBilinear(p.x, p.y)
                }
            }
        }
        return RgbaImage(outWidth, outHeight, out)
    }

    /** Convenience: derive the output size from the quad, then warp. */
    fun warpPerspective(src: RgbaImage, quad: Quad, maxEdge: Int? = null): RgbaImage {
        val (w, h) = outputSizeFor(quad, maxEdge)
        return warpPerspective(src, quad, w, h)
    }

    private fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            var best = abs(a[col][col])
            for (r in col + 1 until n) {
                val v = abs(a[r][col])
                if (v > best) {
                    best = v
                    pivot = r
                }
            }
            require(best > 1e-12) { "degenerate point configuration; cannot solve homography" }
            if (pivot != col) {
                val tmpRow = a[pivot]; a[pivot] = a[col]; a[col] = tmpRow
                val tmpVal = b[pivot]; b[pivot] = b[col]; b[col] = tmpVal
            }
            val pv = a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] / pv
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}
