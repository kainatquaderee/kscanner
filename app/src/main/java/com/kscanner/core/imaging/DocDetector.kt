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
        return detectIn(
            gray = small.toGray(),
            frameWidth = image.width,
            frameHeight = image.height,
            sx = image.width.toDouble() / small.width,
            sy = image.height.toDouble() / small.height,
            aspectHint = aspectHint,
            allowFallbacks = allowFallbacks,
        )
    }

    /**
     * Same pipeline straight from a grayscale frame, so the camera preview can detect without
     * packing its luma plane into RGBA and unpacking it again. A preview frame arrives as one
     * byte per pixel; converting it to colour and back would cost more than the detection.
     */
    fun detect(
        image: GrayImage,
        workingEdge: Int = 700,
        aspectHint: Double? = null,
        allowFallbacks: Boolean = true,
    ): Detection? {
        if (image.width < MIN_SIDE_PX || image.height < MIN_SIDE_PX) return null

        val scale = min(1.0, workingEdge.toDouble() / max(image.width, image.height))
        val small = if (scale < 1.0) {
            Transforms.resizeGray(
                image,
                max(1, (image.width * scale).roundToInt()),
                max(1, (image.height * scale).roundToInt()),
            )
        } else {
            image
        }
        return detectIn(
            gray = small,
            frameWidth = image.width,
            frameHeight = image.height,
            sx = image.width.toDouble() / small.width,
            sy = image.height.toDouble() / small.height,
            aspectHint = aspectHint,
            allowFallbacks = allowFallbacks,
        )
    }

    /**
     * Every outline this frame supports, best first — the same pipeline as [detect], stopped one
     * step earlier.
     *
     * One answer per frame is not enough to follow a page calmly: the ranking of two descriptions
     * of the same page can swap while nothing moves, and a caller that only ever hears the winner
     * has to take each swap as a move. The shortlist the edge stage already builds is the useful
     * part — the tight quad around the sheet is often the second entry — so it is reported rather
     * than discarded.
     *
     * The first element is the answer [detect] gives for the same input: the two must not
     * disagree about which outline the crop would take. The rest follow in descending confidence,
     * which is the only figure comparable across the methods.
     */
    fun candidates(
        image: RgbaImage,
        workingEdge: Int = 700,
        aspectHint: Double? = null,
        allowFallbacks: Boolean = true,
    ): List<Detection> {
        if (image.width < MIN_SIDE_PX || image.height < MIN_SIDE_PX) return emptyList()

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
        return candidatesIn(
            gray = small.toGray(),
            frameWidth = image.width,
            frameHeight = image.height,
            sx = image.width.toDouble() / small.width,
            sy = image.height.toDouble() / small.height,
            aspectHint = aspectHint,
            allowFallbacks = allowFallbacks,
        )
    }

    /** [candidates] straight from a grayscale frame, for the live preview path. */
    fun candidates(
        image: GrayImage,
        workingEdge: Int = 700,
        aspectHint: Double? = null,
        allowFallbacks: Boolean = true,
    ): List<Detection> {
        if (image.width < MIN_SIDE_PX || image.height < MIN_SIDE_PX) return emptyList()

        val scale = min(1.0, workingEdge.toDouble() / max(image.width, image.height))
        val small = if (scale < 1.0) {
            Transforms.resizeGray(
                image,
                max(1, (image.width * scale).roundToInt()),
                max(1, (image.height * scale).roundToInt()),
            )
        } else {
            image
        }
        return candidatesIn(
            gray = small,
            frameWidth = image.width,
            frameHeight = image.height,
            sx = image.width.toDouble() / small.width,
            sy = image.height.toDouble() / small.height,
            aspectHint = aspectHint,
            allowFallbacks = allowFallbacks,
        )
    }

    /**
     * The pipeline proper, on an image already at working scale. [sx] and [sy] scale the result
     * back up to the caller's original resolution.
     */
    private fun detectIn(
        gray: GrayImage,
        /** Size of the image the caller handed in, which the result is expressed in. */
        frameWidth: Int,
        frameHeight: Int,
        sx: Double,
        sy: Double,
        aspectHint: Double?,
        allowFallbacks: Boolean,
    ): Detection? {
        val edge = detectByEdges(gray, aspectHint)
        val settled = edge != null && edge.score >= ACCEPT

        // A confident edge result is final, so the fallbacks are not run at all: each is a
        // segmentation pass of its own. When the edges were not sure, a fallback is only computed
        // while it can still win, which is the order choose() ranks them in.
        val bright = if (!settled && allowFallbacks) detectBrightRegion(gray, aspectHint) else null
        val brightWins = bright != null && (edge == null || bright.score > edge.score)
        val ink = if (!settled && allowFallbacks && !brightWins) detectInkBlock(gray, aspectHint) else null

        val (scored, method) = choose(edge, bright, ink) ?: return null
        return finish(scored, method, sx, sy, frameWidth, frameHeight)
    }

    /**
     * Every candidate in this frame, [detect]'s answer first. The fallbacks are computed
     * unconditionally here — a caller asking for the ranking wants all of it — and carry the same
     * method weights [finish] gives them, so a confidence means the same thing wherever it is read.
     */
    private fun candidatesIn(
        gray: GrayImage,
        frameWidth: Int,
        frameHeight: Int,
        sx: Double,
        sy: Double,
        aspectHint: Double?,
        allowFallbacks: Boolean,
    ): List<Detection> {
        val edges = rankEdges(gray, aspectHint)
        val bright = if (allowFallbacks) detectBrightRegion(gray, aspectHint) else null
        val ink = if (allowFallbacks) detectInkBlock(gray, aspectHint) else null

        val head = choose(edges.firstOrNull(), bright, ink)
        val rest = ArrayList<Detection>(edges.size + 2)
        for (i in edges.indices) {
            if (head != null && head.second == DetectMethod.EDGES && i == 0) continue
            rest.add(finish(edges[i], DetectMethod.EDGES, sx, sy, frameWidth, frameHeight))
        }
        if (head?.second != DetectMethod.BRIGHT_REGION && bright != null) {
            rest.add(finish(bright, DetectMethod.BRIGHT_REGION, sx, sy, frameWidth, frameHeight))
        }
        if (head?.second != DetectMethod.TEXT_BLOCK && ink != null) {
            rest.add(finish(ink, DetectMethod.TEXT_BLOCK, sx, sy, frameWidth, frameHeight))
        }
        rest.sortByDescending { it.confidence }

        if (head == null) return rest
        return listOf(finish(head.first, head.second, sx, sy, frameWidth, frameHeight)) + rest
    }

    /**
     * Which candidate [detect] answers with, given whichever of the three the caller computed.
     *
     * The rule is the acceptance/fallback ordering the pipeline has always used: a confident edge
     * result is final; otherwise a fallback takes over by outscoring the *raw* edge result (the
     * method weights are applied afterwards, so they cannot change who wins); otherwise the edge
     * result is reported even below threshold, because an outline the caller can nudge beats none.
     * Both entry points go through here so they cannot disagree about the crop.
     */
    private fun choose(
        edge: Scored?,
        bright: Scored?,
        ink: Scored?,
    ): Pair<Scored, DetectMethod>? = when {
        edge != null && edge.score >= ACCEPT -> edge to DetectMethod.EDGES
        bright != null && (edge == null || bright.score > edge.score) -> bright to DetectMethod.BRIGHT_REGION
        ink != null && (edge == null || ink.score > edge.score) -> ink to DetectMethod.TEXT_BLOCK
        edge != null -> edge to DetectMethod.EDGES
        else -> null
    }

    // ------------------------------------------------------------------ edges

    private fun detectByEdges(gray: GrayImage, aspectHint: Double?): Scored? =
        rankEdges(gray, aspectHint).firstOrNull()

    /**
     * The edge stage's surviving shortlist, best first. [detectByEdges] is its first element: the
     * shortlist is ordered by the same score that used to be compared one candidate at a time, and
     * the sort is stable, so a tie still goes to the candidate that reached the shortlist list
     * first.
     */
    private fun rankEdges(gray: GrayImage, aspectHint: Double?): List<Scored> {
        val minVotes = max(20, (min(gray.width, gray.height) * 0.18).toInt())
        val field = DocLines.extract(gray, minVotes)
        if (field.familyA.size < 2 || field.familyB.size < 2) return emptyList()

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
        if (shortlist.isEmpty()) return emptyList()

        val survivors = ArrayList<Scored>(shortlist.size)
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

            survivors.add(Scored(candidate.quad, score))
        }
        if (survivors.isEmpty()) return emptyList()
        survivors.sortByDescending { it.score }

        return survivors.map { candidate ->
            val refined = field.refine(candidate.quad)
            if (refined.isConvex() && insideFrame(refined, field.width, field.height)) {
                Scored(refined, candidate.score)
            } else {
                candidate
            }
        }
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
        /** Size of the caller's image; the result is scaled back up to it and clamped to it. */
        frameWidth: Int,
        frameHeight: Int,
    ): Detection {
        val scaledQuad = scored.quad.scaled(sx, sy).clampTo(frameWidth, frameHeight)
        val methodWeight = when (method) {
            DetectMethod.EDGES -> 1.0
            DetectMethod.BRIGHT_REGION -> 0.92
            DetectMethod.TEXT_BLOCK -> 0.80
        }
        val confidence = (scored.score * methodWeight).coerceIn(0.0, 1.0)
        return Detection(scaledQuad, confidence, method, scored.score)
    }
}
