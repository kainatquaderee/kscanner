package com.kscanner.core.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Finds the page outline in a photograph.
 *
 * Strategy: detect edges on a downscaled copy, fit straight lines with a Hough transform,
 * group them into near-horizontal and near-vertical families, and score every candidate
 * quadrilateral formed by intersecting two lines from each family. The best-scoring
 * convex quad wins. A brightness-based fallback covers low-contrast scenes.
 */
object DocDetector {

    enum class Method { EDGES, BRIGHT_REGION }

    data class Result(
        val quad: Quad,
        /** 0..1; higher means the outline was corroborated by strong, well-formed lines. */
        val confidence: Double,
        val method: Method,
    )

    fun detect(image: RgbaImage, workingEdge: Int = 640): Result? {
        if (image.width < 32 || image.height < 32) return null

        val scale = min(1.0, workingEdge.toDouble() / max(image.width, image.height))
        val small = if (scale < 1.0) {
            Transforms.resize(
                image,
                max(1, (image.width * scale).toInt()),
                max(1, (image.height * scale).toInt()),
            )
        } else {
            image
        }

        val sx = image.width.toDouble() / small.width
        val sy = image.height.toDouble() / small.height

        detectByEdges(small)?.let { quad ->
            return Result(
                quad.scaled(sx, sy).clampTo(image.width, image.height),
                0.85,
                Method.EDGES,
            )
        }

        detectByBrightRegion(small)?.let { quad ->
            return Result(
                quad.scaled(sx, sy).clampTo(image.width, image.height),
                0.45,
                Method.BRIGHT_REGION,
            )
        }

        return null
    }

    private fun detectByEdges(small: RgbaImage): Quad? {
        val gray = small.toGray()
        val canny = Edges.canny(gray, sigma = 2.0)
        val minVotes = max(28, (min(small.width, small.height) * 0.22).toInt())
        val lines = Hough.detectLines(canny.edges, minVotes)
        if (lines.size < 4) return null

        var maxMagnitude = 0f
        for (m in canny.magnitude) if (m > maxMagnitude) maxMagnitude = m
        val gradientThreshold = max(20f, maxMagnitude * 0.22f)

        val horizontal = ArrayList<Hough.Line>()
        val vertical = ArrayList<Hough.Line>()
        for (line in lines) {
            val direction = line.directionDeg
            when {
                direction < 40.0 || direction > 140.0 -> horizontal.add(line)
                direction in 50.0..130.0 -> vertical.add(line)
            }
        }
        if (horizontal.size < 2 || vertical.size < 2) return null

        // Deep enough pools that the true page boundary survives even when the page
        // contains many long interior rules (tables, text baselines, form boxes).
        val hCandidates = horizontal.take(10)
        val vCandidates = vertical.take(8)

        var best: Quad? = null
        var bestScore = 0.0

        for (i in hCandidates.indices) {
            for (j in i + 1 until hCandidates.size) {
                for (k in vCandidates.indices) {
                    for (m in k + 1 until vCandidates.size) {
                        val points = listOfNotNull(
                            Hough.intersect(hCandidates[i], vCandidates[k]),
                            Hough.intersect(hCandidates[i], vCandidates[m]),
                            Hough.intersect(hCandidates[j], vCandidates[k]),
                            Hough.intersect(hCandidates[j], vCandidates[m]),
                        )
                        if (points.size != 4) continue
                        if (points.any { it.x.isNaN() || it.y.isNaN() }) continue

                        val quad = runCatching { Quad.order(points) }.getOrNull() ?: continue
                        val votes = hCandidates[i].votes + hCandidates[j].votes +
                            vCandidates[k].votes + vCandidates[m].votes
                        val support = boundarySupport(
                            quad,
                            canny.magnitude,
                            small.width,
                            small.height,
                            gradientThreshold,
                        )
                        val score = scoreQuad(quad, small.width, small.height, votes, support)
                        if (score > bestScore) {
                            bestScore = score
                            best = quad
                        }
                    }
                }
            }
        }
        return best
    }

    /**
     * Fraction of sample points along the quad's perimeter that sit on a real gradient.
     * A quad traced through blank paper scores low; one that follows the page edge scores high.
     */
    private fun boundarySupport(
        quad: Quad,
        magnitude: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): Double {
        val corners = quad.points
        val steps = 48
        var hits = 0
        var samples = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            for (s in 0 until steps) {
                val t = (s + 0.5) / steps
                val x = kotlin.math.round(a.x + (b.x - a.x) * t).toInt()
                val y = kotlin.math.round(a.y + (b.y - a.y) * t).toInt()
                samples++
                var found = false
                search@ for (dy in -2..2) {
                    for (dx in -2..2) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        if (magnitude[ny * width + nx] >= threshold) {
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

    private fun scoreQuad(quad: Quad, width: Int, height: Int, votes: Int, support: Double): Double {
        if (!quad.isConvex()) return 0.0

        val tolX = 0.03 * width
        val tolY = 0.03 * height
        for (p in quad.points) {
            if (p.x < -tolX || p.x > width + tolX || p.y < -tolY || p.y > height + tolY) return 0.0
        }

        val areaRatio = quad.area() / (width.toDouble() * height)
        if (areaRatio < 0.15 || areaRatio > 0.995) return 0.0

        val edges = quad.edgeLengths()
        if (edges.min() / edges.max() < 0.12) return 0.0

        // An outline that does not follow an actual edge is almost certainly wrong.
        if (support < 0.45) return 0.0

        val voteScore = min(1.0, votes / (min(width, height) * 2.0))
        val enclosesCentre = quad.containsPoint(Pt(width / 2.0, height / 2.0))
        val centreBonus = if (enclosesCentre) 0.05 else 0.0

        return areaRatio * 0.5 + voteScore * 0.2 + support * 0.25 + centreBonus
    }

    /** Fallback: the bounding box of the bright, page-like region. */
    private fun detectByBrightRegion(small: RgbaImage): Quad? {
        val gray = small.toGray()
        val threshold = Threshold.otsu(gray)

        var count = 0
        var minX = small.width
        var maxX = -1
        var minY = small.height
        var maxY = -1

        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                if (gray.get(x, y) > threshold) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0 || maxY < 0) return null

        val ratio = count.toDouble() / gray.size
        if (ratio < 0.10 || ratio > 0.96) return null

        val boxWidth = (maxX - minX + 1).toDouble()
        val boxHeight = (maxY - minY + 1).toDouble()
        if (boxWidth < small.width * 0.30 || boxHeight < small.height * 0.30) return null

        return Quad(
            Pt(minX.toDouble(), minY.toDouble()),
            Pt(maxX.toDouble(), minY.toDouble()),
            Pt(maxX.toDouble(), maxY.toDouble()),
            Pt(minX.toDouble(), maxY.toDouble()),
        )
    }
}
