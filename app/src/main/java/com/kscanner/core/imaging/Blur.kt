package com.kscanner.core.imaging

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Summed-area table giving O(1) box sums, which makes every blur and local-mean
 * operation in this pipeline linear in the number of pixels.
 */
class IntegralImage private constructor(
    val width: Int,
    val height: Int,
    private val sums: LongArray,
) {
    private val stride = width + 1

    /** Inclusive box sum over [x0, x1] x [y0, y1]. */
    fun boxSum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
        require(x0 <= x1 && y0 <= y1) { "invalid box ($x0,$y0)-($x1,$y1)" }
        val a = (y1 + 1) * stride + (x1 + 1)
        val b = y0 * stride + (x1 + 1)
        val c = (y1 + 1) * stride + x0
        val d = y0 * stride + x0
        return sums[a] - sums[b] - sums[c] + sums[d]
    }

    companion object {
        fun of(gray: GrayImage): IntegralImage {
            val sums = LongArray((gray.width + 1) * (gray.height + 1))
            fill(gray.data, gray.width, gray.height, sums)
            return IntegralImage(gray.width, gray.height, sums)
        }

        /** Fills a caller-provided buffer so repeated blurs can reuse one allocation. */
        fun fill(channel: IntArray, width: Int, height: Int, dest: LongArray) {
            require(dest.size >= (width + 1) * (height + 1))
            require(channel.size >= width * height)
            val stride = width + 1

            // Row 0 and column 0 are the zero borders; everything else is overwritten below.
            java.util.Arrays.fill(dest, 0, stride, 0L)
            for (y in 1..height) dest[y * stride] = 0L

            for (y in 1..height) {
                var rowSum = 0L
                val srcRow = (y - 1) * width
                val dstRow = y * stride
                val prevRow = (y - 1) * stride
                for (x in 1..width) {
                    rowSum += channel[srcRow + (x - 1)].toLong()
                    dest[dstRow + x] = dest[prevRow + x] + rowSum
                }
            }
        }
    }
}

/** Blur primitives used by edge detection, illumination flattening and sharpening. */
object Blur {

    fun boxBlurGray(src: GrayImage, radius: Int): GrayImage {
        if (radius <= 0) return src.copy()
        val ii = IntegralImage.of(src)
        val out = IntArray(src.size)
        for (y in 0 until src.height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(src.height - 1)
            for (x in 0 until src.width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(src.width - 1)
                val sum = ii.boxSum(x0, y0, x1, y1)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                out[y * src.width + x] = ((sum + count / 2) / count).toInt().coerceIn(0, 255)
            }
        }
        return GrayImage(src.width, src.height, out)
    }

    fun boxBlurRgba(src: RgbaImage, radius: Int): RgbaImage {
        if (radius <= 0) return src.copy()
        val w = src.width
        val h = src.height
        val out = IntArray(src.size)
        val channel = IntArray(src.size)
        val integral = LongArray((w + 1) * (h + 1))

        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until src.size) channel[i] = (src.pixels[i] ushr shift) and 0xFF
            IntegralImage.fill(channel, w, h, integral)
            val stride = w + 1
            for (y in 0 until h) {
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(h - 1)
                for (x in 0 until w) {
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius).coerceAtMost(w - 1)
                    val a = (y1 + 1) * stride + (x1 + 1)
                    val b = y0 * stride + (x1 + 1)
                    val c = (y1 + 1) * stride + x0
                    val d = y0 * stride + x0
                    val sum = integral[a] - integral[b] - integral[c] + integral[d]
                    val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                    val v = ((sum + count / 2) / count).toInt().coerceIn(0, 255)
                    out[y * w + x] = out[y * w + x] or (v shl shift)
                }
            }
        }

        // Alpha is not blurred; scans are opaque and engraving artefacts are undesirable.
        for (i in 0 until src.size) {
            out[i] = out[i] or (src.pixels[i] and -0x1000000)
        }
        return RgbaImage(w, h, out)
    }

    /**
     * Box radii approximating a Gaussian of the given sigma with [passes] iterations.
     * Classical three-pass approximation; each pass is O(n) thanks to the integral image.
     */
    fun boxesForGauss(sigma: Double, passes: Int = 3): IntArray {
        require(sigma > 0 && passes > 0)
        val idealWidth = sqrt(12.0 * sigma * sigma / passes + 1.0)
        var lower = floor(idealWidth).toInt()
        if (lower % 2 == 0) lower--
        if (lower < 1) lower = 1
        val upper = lower + 2
        val idealPasses = (12.0 * sigma * sigma - passes * lower * lower - 4.0 * passes * lower - 3.0 * passes) /
            (-4.0 * lower - 4.0)
        val m = idealPasses.roundToInt()
        return IntArray(passes) { if (it < m) lower else upper }
    }

    fun gaussianBlurGray(src: GrayImage, sigma: Double, passes: Int = 3): GrayImage {
        if (sigma <= 0.0) return src.copy()
        var current = src
        for (box in boxesForGauss(sigma, passes)) {
            current = boxBlurGray(current, (box - 1) / 2)
        }
        return current
    }

    fun gaussianBlurRgba(src: RgbaImage, sigma: Double, passes: Int = 3): RgbaImage {
        if (sigma <= 0.0) return src.copy()
        var current = src
        for (box in boxesForGauss(sigma, passes)) {
            current = boxBlurRgba(current, (box - 1) / 2)
        }
        return current
    }

    /** 3x3 median filter; removes salt-and-pepper noise while keeping edges crisp. */
    fun median3x3Gray(src: GrayImage): GrayImage {
        val out = IntArray(src.size)
        val window = IntArray(9)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        window[n++] = src.getMirrored(x + dx, y + dy)
                    }
                }
                // Insertion sort of nine values; fast in practice.
                for (i in 1 until 9) {
                    val v = window[i]
                    var j = i - 1
                    while (j >= 0 && window[j] > v) {
                        window[j + 1] = window[j]
                        j--
                    }
                    window[j + 1] = v
                }
                out[y * src.width + x] = window[4]
            }
        }
        return GrayImage(src.width, src.height, out)
    }

    /** Majority vote over a 3x3 window; used to clean speckle out of bi-level output. */
    fun majority3x3Gray(src: GrayImage): GrayImage {
        val out = IntArray(src.size)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var dark = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (src.getMirrored(x + dx, y + dy) < 128) dark++
                    }
                }
                out[y * src.width + x] = if (dark >= 5) 0 else 255
            }
        }
        return GrayImage(src.width, src.height, out)
    }
}
