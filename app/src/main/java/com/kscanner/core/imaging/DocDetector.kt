package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a page, card or sheet outline in a photograph.
 *
 * The pipeline, in the order each stage earns its keep:
 *
 * 1. **Edges and lines at one working scale.** Canny, then a Hough transform. The strong
 *    lines are split into two orthogonal families around the *dominant* line direction, so
 *    a page photographed at any angle is handled identically. (This was the old detector's
 *    central failure: it binned lines into "horizontal" and "vertical" relative to the
 *    image, so a tilted page produced no families at all and it fell back to a brightness
 *    box every time.)
 * 2. **Cheap candidate ranking.** Every pair of one line from each family forms a quad.
 *    Cheap geometry — convexity, area plausibility, perimeter ridge support, rectangularity
 *    and votes — prunes this to a shortlist without touching the pixels again.
 * 3. **Contrast arbitration.** Only the shortlist is checked against the actual image: the
 *    inside of a real page is brighter (or darker) than what surrounds it, while a rectangle
 *    drawn through blank paper has no such difference. This is what stops long interior
 *    rules in a form or table from winning.
 * 4. **Sub-pixel refinement.** Each surviving side is snapped to its gradient ridge and the
 *    corners are re-intersected, removing the Hough grid's quantisation.
 * 5. **Fallbacks.** A page that fills the whole frame has no background to contrast against,
 *    so the largest bright region is fitted with a rotated rectangle, and failing that the
 *    ink block itself gives the outline.
 */
object DocDetector {

    /** Minimum score for the edge result to be trusted without trying the fallbacks. */
    private const val ACCEPT = 0.52

    private const val MIN_SIDE_PX = 32

    private data class Scored(val quad: Quad, val score: Double)

    fun detect(
        image: RgbaImage,
        workingEdge: Int = 700,
        /** Expected long/short side ratio of the target, e.g. 1.586 for an ID-1 card. */
        aspectHint: Double? = null,
        allowFallbacks: Boolean = true,
    ): Detection? {
        if (image.width < MIN_SIDE_PX || image.height < MIN_SIDE_PX) return null

        val scale = min(1.0, workingEdge.toDouble() / max(image.width, image.height))
        val small = if (scale < 1.0) {
            Transforms.resize(
                image,
                max(1, (image.width * scale).roundToInt()),
                max(1, (image.height * scale).roundToInt()),
            )
        } else {
            image
        }
        val sx = image.width.toDouble() / small.width
        val sy = image.height.toDouble() / small.height
        val gray = small.toGray()

        val edge = detectByEdges(gray, aspectHint)
        if (edge != null && edge.score >= ACCEPT) {
            return finish(edge, DetectMethod.EDGES, sx, sy, image)
        }

        if (allowFallbacks) {
            val bright = detectBrightRegion(gray, aspectHint)
            if (bright != null && (edge == null || bright.score > edge.score)) {
                return finish(bright, DetectMethod.BRIGHT_REGION, sx, sy, image)
            }
            val ink = detectInkBlock(gray, aspectHint)
            if (ink != null && (edge == null || ink.score > edge.score)) {
                return finish(ink, DetectMethod.TEXT_BLOCK, sx, sy, image)
            }
        }

        // A below-threshold edge result still beats nothing: the caller shows it as a
        // starting outline the user can nudge.
        return edge?.let { finish(it, DetectMethod.EDGES, sx, sy, image) }
    }

    // ------------------------------------------------------------------ edges

    private fun detectByEdges(gray: GrayImage, aspectHint: Double?): Scored? {
        val minVotes = max(20, (min(gray.width, gray.height) * 0.18).toInt())
        val field = DocLines.extract(gray, minVotes)
        if (field.familyA.size < 2 || field.familyB.size < 2) return null

        val shortlist = ArrayList<Scored>(8)
        var worstKept = 0.0
        val a = field.familyA
        val b = field.familyB

        for (i in a.indices) {
            for (j in i + 1 until a.size) {
                for (k in b.indices) {
                    for (m in k + 1 until b.size) {
                        val points = listOfNotNull(
                            Hough.intersect(a[i], b[k]),
                            Hough.intersect(a[i], b[m]),
                            Hough.intersect(a[j], b[k]),
                            Hough.intersect(a[j], b[m]),
                        )
                        if (points.size != 4) continue
                        if (points.any { it.x.isNaN() || it.y.isNaN() }) continue

                        val quad = runCatching { Quad.order(points) }.getOrNull() ?: continue
                        if (!quad.isConvex()) continue
                        if (!insideFrame(quad, field.width, field.height)) continue

                        val areaRatio = quad.area() / (field.width.toDouble() * field.height)
                        if (areaRatio < 0.08 || areaRatio > 0.985) continue

                        val support = field.perimeterSupport(quad)
                        if (support < 0.42) continue

                        val votes = a[i].votes + a[j].votes + b[k].votes + b[m].votes
                        val cheap = 0.40 * support +
                            0.20 * rectangularity(quad) +
                            0.15 * areaScore(areaRatio) +
                            0.10 * voteScore(votes, field.width, field.height) +
                            0.15 * aspectScore(quad, aspectHint)

                        if (shortlist.size < 8) {
                            shortlist.add(Scored(quad, cheap))
                            if (shortlist.size == 8) {
                                shortlist.sortByDescending { it.score }
                                worstKept = shortlist.last().score
                            }
                        } else if (cheap > worstKept) {
                            shortlist[shortlist.size - 1] = Scored(quad, cheap)
                            shortlist.sortByDescending { it.score }
                            worstKept = shortlist.last().score
                        }
                    }
                }
            }
        }
        if (shortlist.isEmpty()) return null

        var best: Scored? = null
        for (candidate in shortlist) {
            val support = field.perimeterSupport(candidate.quad)
            val contrast = interiorContrast(gray, candidate.quad)
            if (contrast < 0.16) continue

            val areaRatio = candidate.quad.area() / (field.width.toDouble() * field.height)
            val votes = votesFor(candidate.quad, a, b)
            var score = 0.34 * support +
                0.24 * contrast +
                0.14 * rectangularity(candidate.quad) +
                0.10 * areaScore(areaRatio) +
                0.08 * voteScore(votes, field.width, field.height) +
                0.10 * aspectScore(candidate.quad, aspectHint)

            // Nudge toward the frame centre only as a tie-break.
            val centre = Pt(field.width / 2.0, field.height / 2.0)
            if (candidate.quad.containsPoint(centre)) score += 0.02

            if (best == null || score > best.score) best = Scored(candidate.quad, score)
        }
        if (best == null) return null

        val refined = field.refine(best.quad)
        if (refined.isConvex() && insideFrame(refined, field.width, field.height)) {
            best = Scored(refined, best.score)
        }
        return best
    }

    /** Total votes of the strongest lines that actually coincide with the quad's sides. */
    private fun votesFor(quad: Quad, a: List<Hough.Line>, b: List<Hough.Line>): Int =
        (a.take(4).sumOf { it.votes } + b.take(4).sumOf { it.votes }) / 2

    // ------------------------------------------------------------------ fallbacks

    private fun detectBrightRegion(gray: GrayImage, aspectHint: Double?): Scored? {
        val threshold = Threshold.otsu(gray)
        val mask = Segmentation.brightMask(gray, threshold)

        var bright = 0
        for (v in mask.data) if (v >= 128) bright++
        val brightFraction = bright.toDouble() / gray.size
        // All-over bright means uniform exposure, not a page on a surface.
        if (brightFraction > 0.94 || brightFraction < 0.05) return null

        val labeled = Segmentation.label(mask, foregroundIsBright = true, minArea = max(64, gray.size / 60))
        val segment = labeled.segments.maxByOrNull { it.area } ?: return null

        val areaRatio = segment.area.toDouble() / gray.size
        if (areaRatio < 0.05 || areaRatio > 0.95) return null
        if (segment.solidity < 0.55) return null

        val hull = Segmentation.convexHull(Segmentation.edgePoints(labeled, segment.label))
        val rect = if (hull.size >= 3) Segmentation.minAreaRect(hull) else null
        val quad = rect ?: quadOf(segment)
        if (!insideFrame(quad, gray.width, gray.height)) return null

        val contrast = interiorContrast(gray, quad)
        if (contrast < 0.12) return null

        val score = 0.45 * contrast +
            0.25 * areaScore(quad.area() / (gray.width.toDouble() * gray.height)) +
            0.15 * segment.solidity +
            0.15 * aspectScore(quad, aspectHint)
        return Scored(quad, score * 0.9)
    }

    private fun detectInkBlock(gray: GrayImage, aspectHint: Double?): Scored? {
        val ink = Segmentation.inkMask(gray, radius = max(6, min(gray.width, gray.height) / 22), bias = 10)

        var inked = 0
        for (v in ink.data) if (v >= 128) inked++
        val inkFraction = inked.toDouble() / gray.size
        if (inkFraction < 0.004 || inkFraction > 0.7) return null

        val bounds = Segmentation.trimmedBounds(ink, 0.012) ?: return null
        val areaRatio = bounds.area() / (gray.width.toDouble() * gray.height)
        if (areaRatio < 0.04 || areaRatio > 0.97) return null

        val contrast = interiorContrast(gray, bounds)
        val score = 0.45 * areaScore(areaRatio) +
            0.25 * aspectScore(bounds, aspectHint) +
            0.20 * min(1.0, inkFraction * 8.0) +
            0.10 * contrast
        return Scored(bounds.insetBy(-0.02), score * 0.85)
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun quadOf(segment: Segment): Quad = Quad(
        Pt(segment.left.toDouble(), segment.top.toDouble()),
        Pt((segment.right + 1).toDouble(), segment.top.toDouble()),
        Pt((segment.right + 1).toDouble(), (segment.bottom + 1).toDouble()),
        Pt(segment.left.toDouble(), (segment.bottom + 1).toDouble()),
    )

    private fun insideFrame(quad: Quad, width: Int, height: Int): Boolean {
        val tolX = 0.04 * width
        val tolY = 0.04 * height
        for (p in quad.points) {
            if (p.x < -tolX || p.x > width + tolX) return false
            if (p.y < -tolY || p.y > height + tolY) return false
        }
        return true
    }

    /** Favours page-like areas: not a speck, not the whole frame. */
    private fun areaScore(ratio: Double): Double = ((ratio - 0.08) / (0.72 - 0.08)).coerceIn(0.0, 1.0)

    private fun voteScore(votes: Int, width: Int, height: Int): Double =
        min(1.0, votes / (min(width, height) * 1.6))

    /**
     * How close the quad is to a rectangle: opposite sides similar in length, corners near
     * 90 degrees. A perspective-distorted page still scores well; a wild quadrilateral does not.
     */
    private fun rectangularity(quad: Quad): Double {
        val e = quad.edgeLengths()
        if (e.min() < 1.0) return 0.0

        val parallel = 1.0 - abs(e[0] - e[2]) / max(e[0], e[2])
        val parallel2 = 1.0 - abs(e[1] - e[3]) / max(e[1], e[3])

        val p = quad.points
        var angleError = 0.0
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val ux = b.x - a.x
            val uy = b.y - a.y
            val vx = c.x - b.x
            val vy = c.y - b.y
            val lu = sqrt(ux * ux + uy * uy)
            val lv = sqrt(vx * vx + vy * vy)
            if (lu < 1e-6 || lv < 1e-6) return 0.0
            angleError += abs((ux * vx + uy * vy) / (lu * lv))
        }
        angleError /= 4.0

        return ((parallel + parallel2) / 2.0 * 0.5 + (1.0 - angleError) * 0.5).coerceIn(0.0, 1.0)
    }

    /** 0..1 agreement with [hint] (either orientation). Neutral 0.6 when no hint is given. */
    private fun aspectScore(quad: Quad, hint: Double?): Double {
        if (hint == null || hint <= 0.0) return 0.6
        val e = quad.edgeLengths()
        val width = (e[0] + e[2]) / 2.0
        val height = (e[1] + e[3]) / 2.0
        if (height < 1e-6) return 0.0
        val ratio = width / height
        val a = abs(ratio - hint) / hint
        val b = abs(ratio - 1.0 / hint) * hint
        return (1.0 - min(a, b) / 0.30).coerceIn(0.0, 1.0)
    }

    /** How much brighter or darker the inside of the quad is than its surroundings. */
    private fun interiorContrast(gray: GrayImage, quad: Quad): Double {
        val inside = Segmentation.meanInside(gray, quad, stride = 6)
        val outside = Segmentation.meanOutside(gray, quad, stride = 6)
        return (abs(inside - outside) / 70.0).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------ finalise

    private fun finish(
        scored: Scored,
        method: DetectMethod,
        sx: Double,
        sy: Double,
        image: RgbaImage,
    ): Detection {
        val scaledQuad = scored.quad.scaled(sx, sy).clampTo(image.width, image.height)
        val methodWeight = when (method) {
            DetectMethod.EDGES -> 1.0
            DetectMethod.BRIGHT_REGION -> 0.92
            DetectMethod.TEXT_BLOCK -> 0.80
        }
        val confidence = (scored.score * methodWeight).coerceIn(0.0, 1.0)
        return Detection(scaledQuad, confidence, method, scored.score)
    }
}
