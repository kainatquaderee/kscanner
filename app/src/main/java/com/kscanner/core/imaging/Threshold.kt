package com.kscanner.core.imaging

import kotlin.math.min
import kotlin.math.roundToInt

/** Global and local thresholding used by the enhancement filters and the detector. */
object Threshold {

    /** Otsu's method: the threshold that maximises between-class variance. */
    fun otsu(gray: GrayImage): Int {
        val hist = IntArray(256)
        for (v in gray.data) hist[v.coerceIn(0, 255)]++

        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]

        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /**
     * Adaptive mean threshold with a constant bias, evaluated on a local window.
     * Produces the crisp bi-level look expected from a document scanner, and unlike a
     * global threshold it survives uneven lighting across a photographed page.
     */
    fun adaptiveMean(gray: GrayImage, radius: Int, bias: Int = 10): GrayImage {
        val r = radius.coerceAtLeast(1)
        val ii = IntegralImage.of(gray)
        val out = IntArray(gray.size)
        for (y in 0 until gray.height) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(gray.height - 1)
            for (x in 0 until gray.width) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(gray.width - 1)
                val sum = ii.boxSum(x0, y0, x1, y1)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val mean = sum / count
                val v = gray.get(x, y)
                out[y * gray.width + x] = if (v > mean - bias) 255 else 0
            }
        }
        return GrayImage(gray.width, gray.height, out)
    }

    /**
     * Flat-field correction: divide out a heavily blurred estimate of the local
     * background so shadows and gradients disappear while text stays dark.
     */
    fun normalizeIllumination(gray: GrayImage, radius: Int, floorLevel: Int = 32): GrayImage {
        val r = radius.coerceAtLeast(1)
        val background = Blur.boxBlurGray(gray, r)
        val out = IntArray(gray.size)
        for (i in 0 until gray.size) {
            val bg = background.data[i].coerceAtLeast(floorLevel)
            val v = gray.data[i]
            out[i] = min(255, (v * 255 + bg / 2) / bg)
        }
        return GrayImage(gray.width, gray.height, out)
    }

    /** Intensity range between two percentiles of the histogram. */
    fun range(gray: GrayImage, lowPercentile: Double = 0.005, highPercentile: Double = 0.995): Pair<Int, Int> {
        val hist = IntArray(256)
        for (v in gray.data) hist[v.coerceIn(0, 255)]++
        val total = gray.size
        val lowTarget = (total * lowPercentile).toInt()
        val highTarget = (total * highPercentile).toInt()

        var acc = 0
        var low = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowTarget) {
                low = i
                break
            }
        }
        acc = 0
        var high = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highTarget) {
                high = i
                break
            }
        }
        return Pair(low, high)
    }

    /** Maps [low, high] onto the full 0..255 range via an exact integer lookup table. */
    fun applyRange(gray: GrayImage, low: Int, high: Int): GrayImage {
        if (high <= low) return gray.copy()
        val range = high - low
        val lut = IntArray(256) { i ->
            when {
                i <= low -> 0
                i >= high -> 255
                // Integer arithmetic avoids the 0.5-off-by-one that double scaling produces.
                else -> ((i - low) * 255 + range / 2) / range
            }
        }
        val out = IntArray(gray.size) { lut[gray.data[it].coerceIn(0, 255)] }
        return GrayImage(gray.width, gray.height, out)
    }

    /** Linear histogram stretch between two percentiles. */
    fun stretch(gray: GrayImage, lowPercentile: Double = 0.005, highPercentile: Double = 0.995): GrayImage {
        val (low, high) = range(gray, lowPercentile, highPercentile)
        return applyRange(gray, low, high)
    }
}
