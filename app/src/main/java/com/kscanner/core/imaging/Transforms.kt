package com.kscanner.core.imaging

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometry-preserving image resampling and reorientation. */
object Transforms {

    /** Bilinear resize to an exact size. */
    fun resize(src: RgbaImage, dstW: Int, dstH: Int): RgbaImage {
        if (dstW == src.width && dstH == src.height) return src.copy()
        require(dstW > 0 && dstH > 0)
        val out = IntArray(dstW * dstH)
        val sx = src.width.toDouble() / dstW
        val sy = src.height.toDouble() / dstH
        var i = 0
        for (y in 0 until dstH) {
            val fy = (y + 0.5) * sy - 0.5
            for (x in 0 until dstW) {
                val fx = (x + 0.5) * sx - 0.5
                out[i++] = src.sampleBilinear(fx, fy)
            }
        }
        return RgbaImage(dstW, dstH, out)
    }

    /** Bilinear resize for grayscale buffers. */
    fun resizeGray(src: GrayImage, dstW: Int, dstH: Int): GrayImage {
        if (dstW == src.width && dstH == src.height) return src.copy()
        require(dstW > 0 && dstH > 0)
        val out = IntArray(dstW * dstH)
        val sx = src.width.toDouble() / dstW
        val sy = src.height.toDouble() / dstH
        var i = 0
        for (y in 0 until dstH) {
            val fy = (y + 0.5) * sy - 0.5
            for (x in 0 until dstW) {
                val fx = (x + 0.5) * sx - 0.5
                out[i++] = sampleGrayBilinear(src, fx, fy)
            }
        }
        return GrayImage(dstW, dstH, out)
    }

    /**
     * Bilinear sample of one channel, clamped at the edges.
     *
     * Shared by [resizeGray] and [rotateArbitrary] on purpose: the deskew correction is only
     * trustworthy if it resamples exactly like the scaling around it does.
     */
    private fun sampleGrayBilinear(src: GrayImage, x: Double, y: Double): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = x - x0
        val ty = y - y0
        val c00 = src.getClamped(x0, y0)
        val c10 = src.getClamped(x0 + 1, y0)
        val c01 = src.getClamped(x0, y0 + 1)
        val c11 = src.getClamped(x0 + 1, y0 + 1)
        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
    }

    /**
     * Scales the image down so its longest edge is at most [maxEdge].
     * Never upscales, so small inputs pass through untouched.
     */
    fun fitLongestEdge(src: RgbaImage, maxEdge: Int): RgbaImage {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toDouble() / longest
        return resize(
            src,
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
        )
    }

    /** Rotates 90 degrees clockwise. */
    fun rotate90(src: RgbaImage): RgbaImage {
        val out = IntArray(src.size)
        val dstW = src.height
        val dstH = src.width
        var i = 0
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                // Inverse of a clockwise quarter turn.
                out[i++] = src.get(dy, src.height - 1 - dx)
            }
        }
        return RgbaImage(dstW, dstH, out)
    }

    fun rotate180(src: RgbaImage): RgbaImage {
        val out = IntArray(src.size)
        for (i in 0 until src.size) out[i] = src.pixels[src.size - 1 - i]
        return RgbaImage(src.width, src.height, out)
    }

    fun rotate270(src: RgbaImage): RgbaImage = rotate90(rotate180(src))

    /** Rotates by a multiple of 90 degrees in the range 0..270 (clockwise). */
    fun rotate(src: RgbaImage, degrees: Int): RgbaImage {
        val d = ((degrees % 360) + 360) % 360
        return when (d) {
            0 -> src
            90 -> rotate90(src)
            180 -> rotate180(src)
            270 -> rotate270(src)
            else -> throw IllegalArgumentException("rotation must be a multiple of 90, was $degrees")
        }
    }

    /**
     * Rotates the picture by an arbitrary angle about its centre, keeping the canvas size.
     *
     * Used for deskew, where the correction is a few degrees at most: an expanded canvas
     * would only add triangular wedges to trim afterwards. Sampling is inverse-mapped with
     * bilinear interpolation, and coordinates outside the source clamp to the edge, so the
     * narrow bands exposed at the corners take their colour from the paper beside them.
     *
     * Positive angles turn the picture clockwise, matching [Skew]'s sign convention.
     */
    fun rotateArbitrary(src: RgbaImage, degrees: Double): RgbaImage {
        if (negligibleTurn(degrees)) return src.copy()
        val out = IntArray(src.size)
        forEachRotatedPixel(src.width, src.height, degrees) { index, sourceX, sourceY ->
            out[index] = src.sampleBilinear(sourceX, sourceY)
        }
        return RgbaImage(src.width, src.height, out)
    }

    /**
     * Gray twin of [rotateArbitrary], for the pages the orientation and skew cues read.
     *
     * Both overloads walk the same [forEachRotatedPixel] mapping: the deskew correction
     * measured in gray space and the one applied to colour pixels have to be the same
     * rotation, and a second copy of the trigonometry is exactly how the two would drift.
     *
     * As in the colour version, samples outside the frame clamp to the edge, so the wedges
     * the rotation exposes replicate the paper beside them instead of turning black.
     */
    fun rotateArbitrary(src: GrayImage, degrees: Double): GrayImage {
        if (negligibleTurn(degrees)) return src.copy()
        val out = IntArray(src.size)
        forEachRotatedPixel(src.width, src.height, degrees) { index, sourceX, sourceY ->
            out[index] = sampleGrayBilinear(src, sourceX, sourceY)
        }
        return GrayImage(src.width, src.height, out)
    }

    /**
     * The one inverse mapping every arbitrary rotation here uses: for each destination pixel
     * in raster order it hands the callback the source coordinate to sample.
     *
     * Inlined because it is called once per pixel, and deliberately the only place the
     * inverse rotation is written down.
     */
    private inline fun forEachRotatedPixel(
        width: Int,
        height: Int,
        degrees: Double,
        emit: (index: Int, sourceX: Double, sourceY: Double) -> Unit,
    ) {
        val rad = Math.toRadians(shortestTurn(degrees))
        val c = cos(rad)
        val s = sin(rad)
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        var index = 0
        for (y in 0 until height) {
            val dy = y - cy
            for (x in 0 until width) {
                val dx = x - cx
                emit(index++, c * dx + s * dy + cx, -s * dx + c * dy + cy)
            }
        }
    }

    /** The shortest signed equivalent of [degrees], so a turn is never taken the long way. */
    private fun shortestTurn(degrees: Double): Double {
        val d = ((degrees % 360.0) + 360.0) % 360.0
        return if (d > 180.0) d - 360.0 else d
    }

    /** True for a turn so small that resampling would only blur the picture. */
    private fun negligibleTurn(degrees: Double): Boolean {
        val shortest = shortestTurn(degrees)
        return shortest > -0.05 && shortest < 0.05
    }

    fun flipHorizontal(src: RgbaImage): RgbaImage {
        val out = IntArray(src.size)
        for (y in 0 until src.height) {
            val row = y * src.width
            for (x in 0 until src.width) {
                out[row + x] = src.pixels[row + (src.width - 1 - x)]
            }
        }
        return RgbaImage(src.width, src.height, out)
    }

    /**
     * Rotates the corner list so it still describes the same physical outline after a
     * 90 degree clockwise turn, re-labelling the corners to preserve tl/tr/br/bl order.
     */
    fun rotateQuad90(quad: Quad, srcWidth: Int, srcHeight: Int): Quad {
        // Matches the pixel mapping in [rotate90]: (x, y) -> (srcHeight - 1 - y, x)
        fun f(p: Pt) = Pt((srcHeight - 1) - p.y, p.x)
        // A clockwise turn sends tl -> tr, tr -> br, br -> bl, bl -> tl.
        return Quad(f(quad.bl), f(quad.tl), f(quad.tr), f(quad.br))
    }
}
