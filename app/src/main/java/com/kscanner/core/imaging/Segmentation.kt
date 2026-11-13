package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A connected region of a binary mask, with the running sums needed for its centroid.
 * Returned in raster order from [Segmentation.label].
 */
data class Segment(
    val label: Int,
    val area: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sumX: Double,
    val sumY: Double,
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
    val centerX: Double get() = sumX / area
    val centerY: Double get() = sumY / area

    /** Share of the bounding box the region actually fills; 1.0 for a solid rectangle. */
    val solidity: Double get() = area.toDouble() / (width.toDouble() * height.toDouble())

    /** Mean width per row, a cheap measure of how stroke-like the region is. */
    val meanRunLength: Double get() = area.toDouble() / height

    fun touchesBorder(frameWidth: Int, frameHeight: Int): Boolean =
        left <= 0 || top <= 0 || right >= frameWidth - 1 || bottom >= frameHeight - 1
}

/** Labels plus the geometry of every region that survived the area filter. */
class LabeledSegments(
    val width: Int,
    val height: Int,
    /** 0 for background and discarded specks, otherwise the index of the segment + 1. */
    val labels: IntArray,
    val segments: List<Segment>,
) {
    fun labelAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else labels[y * width + x]

    fun largest(): Segment? = segments.maxByOrNull { it.area }
}

/**
 * Image segmentation primitives shared by the document detector, the quality analyser and
 * the stain remover. Everything here is plain pixel maths: no framework types, so the
 * behaviour that ships is the behaviour the unit tests exercise.
 */
object Segmentation {

    /**
     * Adaptive ink mask: a pixel is "ink" when it is [bias] darker than the mean of its
     * local window. This survives the uneven lighting of a photographed page in a way a
     * single global threshold cannot.
     */
    fun inkMask(gray: GrayImage, radius: Int = 0, bias: Int = 12): GrayImage {
        val r = if (radius > 0) radius else max(6, min(gray.width, gray.height) / 24)
        val thresholded = Threshold.adaptiveMean(gray, r, bias)
        val out = IntArray(gray.size)
        for (i in 0 until gray.size) {
            // adaptiveMean emits 255 for "at or above the local mean"; ink is the rest.
            out[i] = if (thresholded.data[i] == 0) 255 else 0
        }
        return GrayImage(gray.width, gray.height, out)
    }

    /** Binary mask by a global threshold. */
    fun brightMask(gray: GrayImage, threshold: Int): GrayImage {
        val out = IntArray(gray.size) { if (gray.data[it] >= threshold) 255 else 0 }
        return GrayImage(gray.width, gray.height, out)
    }

    /**
     * Two-pass connected-component labelling with 8-way connectivity.
     *
     * @param minArea regions smaller than this are discarded (speckle, JPEG noise)
     */
    fun label(
        mask: GrayImage,
        foregroundIsBright: Boolean = true,
        minArea: Int = 1,
    ): LabeledSegments {
        val w = mask.width
        val h = mask.height
        val labels = IntArray(mask.size)
        val inside = { i: Int -> if (foregroundIsBright) mask.data[i] >= 128 else mask.data[i] < 128 }

        // Breadth-first labelling with an explicit stack. A second stack records the
        // members so a region that turns out to be too small can be erased without
        // rescanning the whole image.
        val stack = IntArray(mask.size)
        val members = IntArray(mask.size)
        var currentLabel = 0
        val segments = ArrayList<Segment>()

        for (start in 0 until mask.size) {
            if (labels[start] != 0 || !inside(start)) continue

            var top = 0
            var memberCount = 0
            stack[top++] = start
            // Marked with a placeholder so neighbours never re-push it; the real label is
            // written once the region's extent is known.
            labels[start] = -1

            var area = 0
            var l = w
            var r = -1
            var t = h
            var b = -1
            var sx = 0.0
            var sy = 0.0

            while (top > 0) {
                val i = stack[--top]
                members[memberCount++] = i
                val x = i % w
                val y = i / w
                area++
                sx += x
                sy += y
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y

                val y0 = max(0, y - 1)
                val y1 = min(h - 1, y + 1)
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                for (ny in y0..y1) {
                    val row = ny * w
                    for (nx in x0..x1) {
                        val ni = row + nx
                        if (labels[ni] == 0 && inside(ni)) {
                            labels[ni] = -1
                            stack[top++] = ni
                        }
                    }
                }
            }

            if (area < minArea) {
                for (m in 0 until memberCount) labels[members[m]] = 0
                continue
            }

            currentLabel++
            for (m in 0 until memberCount) labels[members[m]] = currentLabel
            segments.add(Segment(currentLabel, area, l, t, r, b, sx, sy))
        }

        return LabeledSegments(w, h, labels, segments)
    }

    /** Pixel coordinates of every member of one labelled region. */
    fun pointsOf(labeled: LabeledSegments, label: Int): List<Pt> {
        val points = ArrayList<Pt>()
        for (i in labeled.labels.indices) {
            if (labeled.labels[i] == label) {
                points.add(Pt((i % labeled.width).toDouble(), (i / labeled.width).toDouble()))
            }
        }
        return points
    }

    /**
     * Boundary samples of one labelled region: the extreme pixels of every row and every
     * column. That is enough points for a tight convex hull while costing one pass over the
     * image instead of allocating a point for every pixel in the region.
     */
    fun edgePoints(labeled: LabeledSegments, label: Int): List<Pt> {
        val w = labeled.width
        val h = labeled.height
        val points = ArrayList<Pt>()
        for (y in 0 until h) {
            val row = y * w
            var lo = -1
            var hi = -1
            for (x in 0 until w) {
                if (labeled.labels[row + x] == label) {
                    if (lo < 0) lo = x
                    hi = x
                }
            }
            if (lo >= 0) {
                points.add(Pt(lo.toDouble(), y.toDouble()))
                points.add(Pt(hi.toDouble(), y.toDouble()))
            }
        }
        for (x in 0 until w) {
            var lo = -1
            var hi = -1
            for (y in 0 until h) {
                if (labeled.labels[y * w + x] == label) {
                    if (lo < 0) lo = y
                    hi = y
                }
            }
            if (lo >= 0) {
                points.add(Pt(x.toDouble(), lo.toDouble()))
                points.add(Pt(x.toDouble(), hi.toDouble()))
            }
        }
        return points
    }

    /** Andrew's monotone chain. Returns the hull counter-clockwise with no repeated point. */
    fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val hull = ArrayList<Pt>(sorted.size * 2)

        fun cross(o: Pt, a: Pt, b: Pt): Double =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull.last(), p) <= 0.0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        val lower = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull.last(), p) <= 0.0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        if (hull.isNotEmpty()) hull.removeAt(hull.size - 1)
        return hull
    }

    /**
     * Smallest-area enclosing rectangle of a convex hull, by rotating calipers.
     *
     * A page photographed at an angle needs a *rotated* rectangle; the axis-aligned
     * bounding box used previously would grow with the tilt and mis-describe the outline.
     */
    fun minAreaRect(hull: List<Pt>): Quad? {
        if (hull.size < 3) return null
        var bestArea = Double.MAX_VALUE
        var bestCorners: List<Pt>? = null

        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val angle = atan2(b.y - a.y, b.x - a.x)
            val cosA = cos(angle)
            val sinA = sin(angle)

            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val u = p.x * cosA + p.y * sinA
                val v = -p.x * sinA + p.y * cosA
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }

            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea && area > 0.0) {
                bestArea = area
                fun back(u: Double, v: Double) = Pt(u * cosA - v * sinA, u * sinA + v * cosA)
                bestCorners = listOf(
                    back(minU, minV),
                    back(maxU, minV),
                    back(maxU, maxV),
                    back(minU, maxV),
                )
            }
        }
        val corners = bestCorners ?: return null
        return runCatching { Quad.order(corners) }.getOrNull()
    }

    /** Number of set pixels per row. */
    fun rowProfile(mask: GrayImage): IntArray {
        val out = IntArray(mask.height)
        for (y in 0 until mask.height) {
            var count = 0
            val row = y * mask.width
            for (x in 0 until mask.width) if (mask.data[row + x] >= 128) count++
            out[y] = count
        }
        return out
    }

    /** Number of set pixels per column. */
    fun columnProfile(mask: GrayImage): IntArray {
        val out = IntArray(mask.width)
        for (y in 0 until mask.height) {
            val row = y * mask.width
            for (x in 0 until mask.width) if (mask.data[row + x] >= 128) out[x]++
        }
        return out
    }

    /**
     * How "peaky" a projection profile is. Text set in rows produces alternating runs of
     * ink and blank space, which maximises this; the same ink smeared across rows by a skew
     * or read along the wrong axis flattens it.
     */
    fun profileSharpness(profile: IntArray): Double {
        if (profile.size < 3) return 0.0
        var total = 0.0
        for (v in profile) total += v
        if (total <= 0.0) return 0.0

        var sumSquares = 0.0
        for (v in profile) {
            val n = v / total
            sumSquares += n * n
        }
        return sumSquares * profile.size
    }

    /** Bounding box of the profile mass, ignoring rows below [fraction] of the peak. */
    fun profileExtent(profile: IntArray, fraction: Double = 0.08): IntRange? {
        val peak = profile.maxOrNull() ?: 0
        if (peak <= 0) return null
        val cut = max(1, (peak * fraction).toInt())
        var first = -1
        var last = -1
        for (i in profile.indices) {
            if (profile[i] >= cut) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) null else first..last
    }

    /**
     * Weighted bounding box of a mask, dropping the [trimFraction] most extreme pixels on
     * each axis. Percentile trimming keeps a stray speck near the frame edge from
     * stretching the answer to the whole image.
     */
    fun trimmedBounds(mask: GrayImage, trimFraction: Double = 0.005): Quad? {
        val rows = rowProfile(mask)
        val columns = columnProfile(mask)
        val rowRange = profileExtent(rows, trimFraction) ?: return null
        val columnRange = profileExtent(columns, trimFraction) ?: return null
        return Quad(
            Pt(columnRange.first.toDouble(), rowRange.first.toDouble()),
            Pt(columnRange.last.toDouble(), rowRange.first.toDouble()),
            Pt(columnRange.last.toDouble(), rowRange.last.toDouble()),
            Pt(columnRange.first.toDouble(), rowRange.last.toDouble()),
        )
    }

    /** Mean luma of the samples whose coordinates are inside the quad. */
    fun meanInside(gray: GrayImage, quad: Quad, stride: Int = 4): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until gray.height step stride) {
            for (x in 0 until gray.width step stride) {
                if (quad.containsPoint(Pt(x + 0.5, y + 0.5))) {
                    sum += gray.get(x, y)
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Mean luma of the samples outside the quad, within [band] pixels of its centre. */
    fun meanOutside(gray: GrayImage, quad: Quad, stride: Int = 4): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until gray.height step stride) {
            for (x in 0 until gray.width step stride) {
                if (!quad.containsPoint(Pt(x + 0.5, y + 0.5))) {
                    sum += gray.get(x, y)
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Root-mean-square distance of a point set from a line, plus the fitted direction. */
    fun fitLine(points: List<Pt>): Pair<Pt, Double>? {
        if (points.size < 2) return null
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val angle = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return Pair(Pt(cx, cy), angle)
    }

    /** Intersection of two lines given as point + direction angle. */
    fun intersectLines(a: Pair<Pt, Double>, b: Pair<Pt, Double>): Pt? {
        val (pa, ta) = a
        val (pb, tb) = b
        val dxa = cos(ta)
        val dya = sin(ta)
        val dxb = cos(tb)
        val dyb = sin(tb)
        val det = dxa * dyb - dya * dxb
        if (abs(det) < 1e-9) return null
        val t = ((pb.x - pa.x) * dyb - (pb.y - pa.y) * dxb) / det
        return Pt(pa.x + dxa * t, pa.y + dya * t)
    }

    /** Distance from a point to a line given as point + direction angle. */
    fun distanceToLine(p: Pt, line: Pair<Pt, Double>): Double {
        val (o, angle) = line
        val dx = p.x - o.x
        val dy = p.y - o.y
        return abs(-sin(angle) * dx + cos(angle) * dy)
    }
}
