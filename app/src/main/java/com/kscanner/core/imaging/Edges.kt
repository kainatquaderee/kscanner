package com.kscanner.core.imaging

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/** Canny edge detection over single-channel buffers. */
object Edges {

    data class CannyResult(
        /** Edge map: 255 on an edge, 0 elsewhere. */
        val edges: GrayImage,
        /** Gradient magnitude before thresholding. */
        val magnitude: FloatArray,
    )

    /**
     * @param lowThreshold  lower hysteresis bound; pass a negative value to derive it from the image
     * @param highThreshold upper hysteresis bound; pass a negative value to derive it from the image
     */
    fun canny(
        gray: GrayImage,
        sigma: Double = 1.4,
        lowThreshold: Double = -1.0,
        highThreshold: Double = -1.0,
    ): CannyResult {
        val w = gray.width
        val h = gray.height
        val blurred = Blur.gaussianBlurGray(gray, sigma)

        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)

        var maxMag = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val tl = blurred.getMirrored(x - 1, y - 1)
                val t = blurred.getMirrored(x, y - 1)
                val tr = blurred.getMirrored(x + 1, y - 1)
                val l = blurred.getMirrored(x - 1, y)
                val r = blurred.getMirrored(x + 1, y)
                val bl = blurred.getMirrored(x - 1, y + 1)
                val b = blurred.getMirrored(x, y + 1)
                val br = blurred.getMirrored(x + 1, y + 1)

                val sx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val sy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                val i = y * w + x
                gx[i] = sx.toFloat()
                gy[i] = sy.toFloat()
                val m = sqrt((sx * sx + sy * sy).toDouble()).toFloat()
                mag[i] = m
                if (m > maxMag) maxMag = m
            }
        }

        val resolvedHigh = if (highThreshold > 0) highThreshold else (maxMag * 0.22).toDouble().coerceAtLeast(12.0)
        val resolvedLow = if (lowThreshold > 0) lowThreshold else resolvedHigh * 0.4

        // Non-maximum suppression: thin ridges down to single-pixel lines.
        val suppressed = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val m = mag[i]
                if (m <= 0f) continue
                var angle = atan2(gy[i].toDouble(), gx[i].toDouble()) * 180.0 / PI
                angle = ((angle % 180.0) + 180.0) % 180.0

                val (a, b) = when {
                    angle < 22.5 || angle >= 157.5 ->
                        magAt(mag, w, h, x - 1, y) to magAt(mag, w, h, x + 1, y)
                    angle < 67.5 ->
                        magAt(mag, w, h, x + 1, y - 1) to magAt(mag, w, h, x - 1, y + 1)
                    angle < 112.5 ->
                        magAt(mag, w, h, x, y - 1) to magAt(mag, w, h, x, y + 1)
                    else ->
                        magAt(mag, w, h, x - 1, y - 1) to magAt(mag, w, h, x + 1, y + 1)
                }
                if (m >= a && m >= b) suppressed[i] = m
            }
        }

        // Hysteresis: strong pixels seed, weak-but-connected pixels follow.
        val out = IntArray(w * h)
        val stack = IntArray(w * h)
        var stackSize = 0
        for (i in 0 until w * h) {
            if (suppressed[i] >= resolvedHigh) {
                out[i] = 255
                if (stackSize < stack.size) stack[stackSize++] = i
            }
        }
        while (stackSize > 0) {
            val i = stack[--stackSize]
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (out[ni] == 0 && suppressed[ni] >= resolvedLow) {
                        out[ni] = 255
                        if (stackSize < stack.size) stack[stackSize++] = ni
                    }
                }
            }
        }

        return CannyResult(GrayImage(w, h, out), mag)
    }

    private fun magAt(mag: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        if (x < 0 || y < 0 || x >= w || y >= h) return -1f
        return mag[y * w + x]
    }
}
