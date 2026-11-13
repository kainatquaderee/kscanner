package com.kscanner.core.imaging

/**
 * Packed 0xAARRGGBB pixel helpers.
 *
 * These buffers are deliberately framework-free (no `android.graphics.*`) so the whole
 * imaging pipeline can be exercised by plain JVM unit tests.
 */
object Px {
    fun a(c: Int): Int = (c ushr 24) and 0xFF
    fun r(c: Int): Int = (c ushr 16) and 0xFF
    fun g(c: Int): Int = (c ushr 8) and 0xFF
    fun b(c: Int): Int = c and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    fun clamp255(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    fun clamp255(v: Double): Int = if (v < 0.0) 0 else if (v > 255.0) 255 else v.toInt()

    /** Rec.601 luma, rounded. */
    fun luma(r: Int, g: Int, b: Int): Int = (0.299 * r + 0.587 * g + 0.114 * b + 0.5).toInt()
}

/** A mutable 32-bit ARGB image buffer. */
class RgbaImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "invalid image size ${width}x$height" }
        require(pixels.size == width * height) {
            "buffer holds ${pixels.size} pixels but size is ${width * height}"
        }
    }

    val size: Int get() = width * height

    fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun set(x: Int, y: Int, color: Int) {
        pixels[y * width + x] = color
    }

    fun getClamped(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return pixels[cy * width + cx]
    }

    fun copy(): RgbaImage = RgbaImage(width, height, pixels.copyOf())

    /** Bilinear sample in continuous coordinates. */
    fun sampleBilinear(x: Double, y: Double): Int {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val x1 = x0 + 1
        val y1 = y0 + 1
        val x0c = if (x0 < 0) 0 else if (x0 >= width) width - 1 else x0
        val y0c = if (y0 < 0) 0 else if (y0 >= height) height - 1 else y0
        val x1c = if (x1 < 0) 0 else if (x1 >= width) width - 1 else x1
        val y1c = if (y1 < 0) 0 else if (y1 >= height) height - 1 else y1

        fun channel(shift: Int): Int {
            val c00 = (pixels[y0c * width + x0c] ushr shift) and 0xFF
            val c10 = (pixels[y0c * width + x1c] ushr shift) and 0xFF
            val c01 = (pixels[y1c * width + x0c] ushr shift) and 0xFF
            val c11 = (pixels[y1c * width + x1c] ushr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            val v = top + (bottom - top) * fy
            return v.toInt().coerceIn(0, 255)
        }

        return Px.argb(channel(24), channel(16), channel(8), channel(0))
    }

    fun toGray(): GrayImage {
        val out = IntArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            out[i] = Px.luma(Px.r(c), Px.g(c), Px.b(c))
        }
        return GrayImage(width, height, out)
    }

    companion object {
        fun filled(width: Int, height: Int, color: Int) = RgbaImage(width, height, IntArray(width * height) { color })

        fun fromGray(gray: GrayImage): RgbaImage {
            val out = IntArray(gray.size)
            for (i in 0 until gray.size) {
                val v = gray.data[i].coerceIn(0, 255)
                out[i] = Px.rgb(v, v, v)
            }
            return RgbaImage(gray.width, gray.height, out)
        }
    }
}

/** An 8-bit single-channel buffer; values are stored as 0..255 ints for clarity. */
class GrayImage(val width: Int, val height: Int, val data: IntArray) {

    init {
        require(width > 0 && height > 0) { "invalid image size ${width}x$height" }
        require(data.size == width * height) {
            "buffer holds ${data.size} samples but size is ${width * height}"
        }
    }

    val size: Int get() = width * height

    fun get(x: Int, y: Int): Int = data[y * width + x]

    fun set(x: Int, y: Int, v: Int) {
        data[y * width + x] = v
    }

    fun getClamped(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return data[cy * width + cx]
    }

    fun getMirrored(x: Int, y: Int): Int {
        var cx = x
        var cy = y
        if (cx < 0) cx = -cx
        if (cy < 0) cy = -cy
        if (cx >= width) cx = 2 * width - 2 - cx
        if (cy >= height) cy = 2 * height - 2 - cy
        cx = cx.coerceIn(0, width - 1)
        cy = cy.coerceIn(0, height - 1)
        return data[cy * width + cx]
    }

    fun copy(): GrayImage = GrayImage(width, height, data.copyOf())

    fun toRgba(): RgbaImage = RgbaImage.fromGray(this)

    companion object {
        fun filled(width: Int, height: Int, value: Int) = GrayImage(width, height, IntArray(width * height) { value })
    }
}
