package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in continuous image coordinates (x right, y down). */
data class Pt(val x: Double, val y: Double) {
    fun dist(o: Pt): Double = hypot(x - o.x, y - o.y)

    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)

    companion object {
        fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

/**
 * A document outline: four corners in clockwise screen order (y grows downward),
 * so `tl -> tr -> br -> bl` traces the page boundary.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Shoelace area; always non-negative. */
    fun area(): Double {
        val p = points
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    fun center(): Pt = Pt(points.sumOf { it.x } / 4.0, points.sumOf { it.y } / 4.0)

    fun edgeLengths(): DoubleArray = doubleArrayOf(
        tl.dist(tr),
        tr.dist(br),
        br.dist(bl),
        bl.dist(tl),
    )

    /** Longest top/bottom edge and longest left/right edge, used to size the unwarped output. */
    fun unwarpedSize(): Pair<Int, Int> {
        val e = edgeLengths()
        val w = max(e[0], e[2])
        val h = max(e[1], e[3])
        return Pair(max(1.0, w).toInt(), max(1.0, h).toInt())
    }

    /** True when every turn has the same orientation (no self-intersection). */
    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Scales the outline inward about its centroid by [fraction]; `0.1` keeps 90% of the
     * original size, i.e. it pulls every edge in by 5% of the span.
     */
    fun insetBy(fraction: Double): Quad {
        val c = center()
        val t = 1.0 - fraction
        fun f(p: Pt) = Pt(c.x + (p.x - c.x) * t, c.y + (p.y - c.y) * t)
        return Quad(f(tl), f(tr), f(br), f(bl))
    }

    fun clampTo(width: Int, height: Int): Quad {
        fun f(p: Pt) = Pt(p.x.coerceIn(0.0, (width - 1).toDouble()), p.y.coerceIn(0.0, (height - 1).toDouble()))
        return Quad(f(tl), f(tr), f(br), f(bl))
    }

    fun translated(dx: Double, dy: Double): Quad =
        Quad(tl + Pt(dx, dy), tr + Pt(dx, dy), br + Pt(dx, dy), bl + Pt(dx, dy))

    fun scaled(sx: Double, sy: Double): Quad =
        Quad(
            Pt(tl.x * sx, tl.y * sy),
            Pt(tr.x * sx, tr.y * sy),
            Pt(br.x * sx, br.y * sy),
            Pt(bl.x * sx, bl.y * sy),
        )

    /**
     * Describes the same physical outline after a clockwise quarter turn, expressed in
     * normalised coordinates. `(x, y) -> (1 - y, x)` matches the pixel mapping in
     * [Transforms.rotate90], and the corners are re-labelled so tl/tr/br/bl still hold.
     */
    fun rotated90Normalized(): Quad {
        fun f(p: Pt) = Pt(1.0 - p.y, p.x)
        return Quad(f(bl), f(tl), f(tr), f(br))
    }

    /** Counter-clockwise quarter turn in normalised coordinates. */
    fun rotated270Normalized(): Quad = rotated90Normalized().rotated90Normalized().rotated90Normalized()

    fun containsPoint(p: Pt): Boolean {
        // Convex polygon test: p must be on the same side of every edge.
        val poly = points
        var pos = false
        var neg = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % 4]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            if (cross > 0) pos = true
            if (cross < 0) neg = true
        }
        return !(pos && neg)
    }

    companion object {
        fun rect(width: Double, height: Double): Quad =
            Quad(Pt(0.0, 0.0), Pt(width, 0.0), Pt(width, height), Pt(0.0, height))

        /** Default outline used when automatic detection finds nothing: a small inset of the full frame. */
        fun insetFrame(width: Int, height: Int, fraction: Double = 0.04): Quad =
            rect(width.toDouble(), height.toDouble()).insetBy(fraction)

        /**
         * Orders an arbitrary set of four points into [tl, tr, br, bl] by walking them
         * counter-clockwise around their centroid, then rotating so the corner nearest
         * the top-left of the bounding box comes first.
         */
        fun order(points: List<Pt>): Quad {
            require(points.size == 4) { "expected 4 points, got ${points.size}" }
            val cx = points.sumOf { it.x } / 4.0
            val cy = points.sumOf { it.y } / 4.0
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            var startIndex = 0
            var best = Double.MAX_VALUE
            sorted.forEachIndexed { i, p ->
                val score = p.x + p.y
                if (score < best) {
                    best = score
                    startIndex = i
                }
            }
            val r = List(4) { sorted[(startIndex + it) % 4] }
            val candidate = Quad(r[0], r[1], r[2], r[3])
            // The angle sort yields a consistent winding; if it landed the wrong way, flip it.
            return if (candidate.isConvex()) candidate else Quad(r[0], r[3], r[2], r[1])
        }
    }
}
