package com.kscanner.core.imaging

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a camera frame's luma plane into a [GrayImage].
 *
 * CameraX hands analysis frames over as YUV, whose first plane is already the luma we want
 * — no colour conversion is needed for measuring sharpness or exposure. The plane is
 * strided: rows are padded out to `rowStride` and pixels can be interleaved, so both have to
 * be walked rather than assumed packed.
 *
 * Deliberately takes a plain [ByteBuffer] rather than an Android `ImageProxy`, so the
 * arithmetic (which is where the bugs live) is covered by ordinary JVM tests.
 */
object Luma {

    /**
     * Reads a strided luma plane, optionally skipping pixels to land near [targetEdge].
     *
     * @param step sample every `step`-th pixel in both directions; must be at least 1.
     */
    fun grayFromPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1,
        step: Int = 1,
    ): GrayImage {
        require(width > 0 && height > 0) { "invalid frame ${width}x$height" }
        val s = max(1, step)
        val outWidth = max(1, (width + s - 1) / s)
        val outHeight = max(1, (height + s - 1) / s)
        val out = IntArray(outWidth * outHeight)
        val limit = buffer.limit()

        var i = 0
        for (oy in 0 until outHeight) {
            val sy = (oy * s).coerceAtMost(height - 1)
            val rowStart = sy * rowStride
            for (ox in 0 until outWidth) {
                val sx = (ox * s).coerceAtMost(width - 1)
                val index = rowStart + sx * pixelStride
                out[i++] = if (index in 0 until limit) buffer.get(index).toInt() and 0xFF else 0
            }
        }
        return GrayImage(outWidth, outHeight, out)
    }

    /** Largest integer step that keeps the longer edge at or below [targetEdge]. */
    fun stepFor(width: Int, height: Int, targetEdge: Int): Int {
        val longest = max(width, height)
        if (longest <= targetEdge) return 1
        return max(1, (longest.toDouble() / targetEdge).roundToInt())
    }

    /**
     * A frame sampled for detection, and the description of the frame it is in.
     *
     * The two travel together because only together are they true: an outline found in [gray] is
     * expressed in [gray]'s pixels, and [frame] is what a caller needs to put it on screen. Describing
     * the same outline with the camera's own size is a silent error of [stepFor] — the outline is
     * then drawn at `1/step` of its size, about the wrong centre, which is far enough to put it
     * beside the page and, often, outside the screen.
     */
    data class SampledFrame(val gray: GrayImage, val frame: PreviewFrame)

    /**
     * Reads the plane at [targetEdge], and reports the size of *that* image.
     *
     * @param rotationDegrees the turn the camera says puts the frame upright; it applies unchanged
     *   to the sampled image, whose axes are the frame's axes.
     */
    fun sampleFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1,
        targetEdge: Int,
        rotationDegrees: Int = 0,
    ): SampledFrame {
        val step = stepFor(width, height, targetEdge)
        val gray = grayFromPlane(buffer, width, height, rowStride, pixelStride, step)
        return SampledFrame(gray, PreviewFrame(gray.width, gray.height, rotationDegrees))
    }
}
