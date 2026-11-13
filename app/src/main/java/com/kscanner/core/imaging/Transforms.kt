package com.kscanner.core.imaging

import kotlin.math.max
import kotlin.math.roundToInt

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
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (x in 0 until dstW) {
                val fx = (x + 0.5) * sx - 0.5
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                val c00 = src.getClamped(x0, y0)
                val c10 = src.getClamped(x0 + 1, y0)
                val c01 = src.getClamped(x0, y0 + 1)
                val c11 = src.getClamped(x0 + 1, y0 + 1)
                val top = c00 + (c10 - c00) * tx
                val bottom = c01 + (c11 - c01) * tx
                out[i++] = (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
            }
        }
        return GrayImage(dstW, dstH, out)
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
