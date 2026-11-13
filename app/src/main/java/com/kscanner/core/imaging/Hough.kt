package com.kscanner.core.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classic Hough transform for straight lines.
 *
 * Lines are stored in normal form: `x*cos(theta) + y*sin(theta) = rho`, with theta in
 * radians over `[0, PI)` and rho measured from the image centre.
 */
object Hough {

    data class Line(val theta: Double, val rho: Double, val votes: Int) {
        /** Direction the line runs in, in degrees within [0, 180). */
        val directionDeg: Double
            get() {
                val d = (theta * 180.0 / PI + 90.0) % 180.0
                return if (d < 0) d + 180.0 else d
            }
    }

    fun detectLines(edges: GrayImage, minVotes: Int, maxLines: Int = 150): List<Line> {
        val w = edges.width
        val h = edges.height
        val diag = ceil(sqrt((w * w + h * h).toDouble())).toInt()
        val nRho = 2 * diag + 1
        val nTheta = 180

        val accumulator = IntArray(nTheta * nRho)
        val cosT = DoubleArray(nTheta)
        val sinT = DoubleArray(nTheta)
        for (t in 0 until nTheta) {
            val theta = t * PI / 180.0
            cosT[t] = cos(theta)
            sinT[t] = sin(theta)
        }

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (edges.data[row + x] < 128) continue
                for (t in 0 until nTheta) {
                    val rho = x * cosT[t] + y * sinT[t]
                    accumulator[t * nRho + diag + rho.roundToInt()]++
                }
            }
        }

        // Local maxima, suppressing responses that bleed into neighbouring cells.
        val candidates = ArrayList<Line>()
        for (t in 0 until nTheta) {
            for (r in 0 until nRho) {
                val votes = accumulator[t * nRho + r]
                if (votes < minVotes) continue
                var isPeak = true
                scan@ for (dt in -2..2) {
                    val tt = ((t + dt) % nTheta + nTheta) % nTheta
                    for (dr in -3..3) {
                        if (dt == 0 && dr == 0) continue
                        val rr = r + dr
                        if (rr < 0 || rr >= nRho) continue
                        if (accumulator[tt * nRho + rr] > votes) {
                            isPeak = false
                            break@scan
                        }
                    }
                }
                if (isPeak) candidates.add(Line(t * PI / 180.0, (r - diag).toDouble(), votes))
            }
        }

        candidates.sortByDescending { it.votes }

        // Collapse near-identical detections into one representative.
        val merged = ArrayList<Line>()
        for (candidate in candidates) {
            val duplicate = merged.any { existing ->
                abs(existing.theta - candidate.theta) < 3.0 * PI / 180.0 &&
                    abs(existing.rho - candidate.rho) < 10.0
            }
            if (!duplicate) {
                merged.add(candidate)
                if (merged.size >= maxLines) break
            }
        }
        return merged
    }

    /** Intersection of two lines, or null when they are parallel. */
    fun intersect(a: Line, b: Line): Pt? {
        val det = cos(a.theta) * sin(b.theta) - sin(a.theta) * cos(b.theta)
        if (abs(det) < 1e-6) return null
        val x = (a.rho * sin(b.theta) - b.rho * sin(a.theta)) / det
        val y = (b.rho * cos(a.theta) - a.rho * cos(b.theta)) / det
        return Pt(x, y)
    }
}
