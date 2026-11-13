package com.kscanner.core.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Enhancement presets offered in the filter screen. */
enum class ScanFilter(val id: Int, val label: String, val description: String) {
    ORIGINAL(0, "Original", "Untouched capture"),
    MAGIC_COLOR(1, "Magic Color", "Balanced colour and bright paper"),
    ENHANCE(2, "Enhance", "Sharper with deeper contrast"),
    GRAYSCALE(3, "Grayscale", "Neutral grey tones"),
    BLACK_WHITE(4, "B&W", "Crisp text on white"),
    LIGHTEN(5, "Lighten", "Lifts shadows and stains"),
    SHADOW_FREE(6, "Shadow Free", "Flattens shadows and uneven light");

    companion object {
        fun fromId(id: Int): ScanFilter = entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/**
 * All enhancement is expressed as a full-frame pixel operation with no framework
 * dependencies, so every preset is reproducible in unit tests.
 */
object Filters {

    fun apply(src: RgbaImage, filter: ScanFilter, strength: Float = 1f): RgbaImage {
        val amount = strength.coerceIn(0f, 1f)
        if (filter == ScanFilter.ORIGINAL || amount <= 0f) return src.copy()

        val processed = when (filter) {
            ScanFilter.ORIGINAL -> src.copy()
            ScanFilter.MAGIC_COLOR -> magicColor(src)
            ScanFilter.ENHANCE -> enhance(src)
            ScanFilter.GRAYSCALE -> grayscale(src)
            ScanFilter.BLACK_WHITE -> blackAndWhite(src)
            ScanFilter.LIGHTEN -> lighten(src)
            ScanFilter.SHADOW_FREE -> shadowFree(src)
        }
        return if (amount >= 1f) processed else blend(src, processed, amount)
    }

    /**
     * Chooses a preset from the frame's own measurements rather than asking the user.
     *
     * A page lit from one side reads high on the illumination ramp and wants the flat-field
     * correction; a low-contrast but inky page wants the contrast boost; everything else
     * gets the all-rounder.
     */
    fun auto(src: RgbaImage): ScanFilter {
        val gray = src.toGray()
        val report = Quality.analyse(gray)
        return when {
            report.illuminationRamp > 0.30 || report.shadowHeavy -> ScanFilter.SHADOW_FREE
            report.inkFraction > 0.03 && report.contrast < 55.0 -> ScanFilter.ENHANCE
            report.meanLuma > 235.0 -> ScanFilter.BLACK_WHITE
            else -> ScanFilter.MAGIC_COLOR
        }
    }

    fun blend(a: RgbaImage, b: RgbaImage, t: Float): RgbaImage {
        require(a.width == b.width && a.height == b.height) { "blend requires matching sizes" }
        val f = t.coerceIn(0f, 1f)
        if (f <= 0f) return a.copy()
        if (f >= 1f) return b.copy()
        val out = IntArray(a.size)
        for (i in 0 until a.size) {
            val pa = a.pixels[i]
            val pb = b.pixels[i]
            out[i] = Px.argb(
                Px.a(pa),
                (Px.r(pa) + (Px.r(pb) - Px.r(pa)) * f).roundToInt().coerceIn(0, 255),
                (Px.g(pa) + (Px.g(pb) - Px.g(pa)) * f).roundToInt().coerceIn(0, 255),
                (Px.b(pa) + (Px.b(pb) - Px.b(pa)) * f).roundToInt().coerceIn(0, 255),
            )
        }
        return RgbaImage(a.width, a.height, out)
    }

    /** Unsharp masking: subtract a blurred copy and add the difference back, scaled. */
    fun unsharpMask(src: RgbaImage, amount: Float, sigma: Double = 1.2): RgbaImage {
        if (amount <= 0f) return src.copy()
        val blurred = Blur.gaussianBlurRgba(src, sigma)
        val out = IntArray(src.size)
        for (i in 0 until src.size) {
            val p = src.pixels[i]
            val q = blurred.pixels[i]
            out[i] = Px.argb(
                Px.a(p),
                Px.clamp255(Px.r(p) + amount * (Px.r(p) - Px.r(q))),
                Px.clamp255(Px.g(p) + amount * (Px.g(p) - Px.g(q))),
                Px.clamp255(Px.b(p) + amount * (Px.b(p) - Px.b(q))),
            )
        }
        return RgbaImage(src.width, src.height, out)
    }

    /**
     * Manual adjustments applied on top of a preset.
     * @param brightness -1..1 (additive tint)
     * @param contrast   -1..1 (negative flattens, positive deepens)
     * @param sharpness  0..1 (unsharp amount)
     */
    fun adjust(src: RgbaImage, brightness: Float, contrast: Float, sharpness: Float): RgbaImage {
        val b = brightness.coerceIn(-1f, 1f) * 100f
        val c = contrast.coerceIn(-1f, 1f)
        val factor = if (c >= 0f) 1f + c * 1.5f else 1f + c * 0.9f

        val lut = IntArray(256) { i ->
            ((i - 128f) * factor + 128f + b).roundToInt().coerceIn(0, 255)
        }
        val out = IntArray(src.size)
        for (i in 0 until src.size) {
            val p = src.pixels[i]
            out[i] = Px.argb(Px.a(p), lut[Px.r(p)], lut[Px.g(p)], lut[Px.b(p)])
        }
        var result = RgbaImage(src.width, src.height, out)

        val sharp = sharpness.coerceIn(0f, 1f)
        if (sharp > 0f) result = unsharpMask(result, sharp * 1.2f)
        return result
    }

    // ---------------------------------------------------------------- presets

    private fun grayscale(src: RgbaImage): RgbaImage {
        val stretched = Threshold.stretch(src.toGray(), 0.004, 0.996)
        return stretched.toRgba()
    }

    private fun magicColor(src: RgbaImage): RgbaImage {
        val out = RgbaImage(src.width, src.height, src.pixels.copyOf())

        // Gray-world assumption pulls a colour cast out of the paper.
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (p in src.pixels) {
            sumR += Px.r(p)
            sumG += Px.g(p)
            sumB += Px.b(p)
        }
        val n = src.size.toDouble()
        val meanR = sumR / n
        val meanG = sumG / n
        val meanB = sumB / n
        val meanAll = (meanR + meanG + meanB) / 3.0

        fun gain(mean: Double): Double =
            if (mean < 1.0) 1.0 else (meanAll / mean).coerceIn(0.85, 1.18)

        val gR = gain(meanR)
        val gG = gain(meanG)
        val gB = gain(meanB)

        if (gR != 1.0 || gG != 1.0 || gB != 1.0) {
            for (i in 0 until src.size) {
                val p = src.pixels[i]
                out.pixels[i] = Px.argb(
                    Px.a(p),
                    Px.clamp255(Px.r(p) * gR),
                    Px.clamp255(Px.g(p) * gG),
                    Px.clamp255(Px.b(p) * gB),
                )
            }
        }

        // Per-channel percentile stretch whitens the paper without clipping the ink.
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = channelAsGray(out, shift)
            val (low, high) = Threshold.range(channel, 0.004, 0.996)
            writeChannel(out, shift, Threshold.applyRange(channel, low, high))
        }

        return saturateAndContrast(out, saturation = 1.15f, contrast = 0.10f)
    }

    private fun lighten(src: RgbaImage): RgbaImage {
        val gray = src.toGray()
        val radius = max(8, max(src.width, src.height) / 8)
        val flattened = Threshold.normalizeIllumination(gray, radius, floorLevel = 48)
        val lifted = Threshold.stretch(flattened, 0.002, 0.998)

        val out = IntArray(src.size)
        for (i in 0 until src.size) {
            val source = gray.data[i].coerceAtLeast(1)
            val gain = (lifted.data[i].toDouble() / source).coerceIn(0.0, 3.2)
            val p = src.pixels[i]
            out[i] = Px.argb(
                Px.a(p),
                Px.clamp255(Px.r(p) * gain),
                Px.clamp255(Px.g(p) * gain),
                Px.clamp255(Px.b(p) * gain),
            )
        }
        return RgbaImage(src.width, src.height, out)
    }

    /**
     * Flat-field correction in colour: divide out a heavily blurred estimate of the scene's
     * own lighting, channel by channel, then stretch. A shadow or a gradient across the page
     * becomes flat white while the ink stays dark, which a global curve cannot do.
     */
    private fun shadowFree(src: RgbaImage): RgbaImage {
        val radius = max(12, max(src.width, src.height) / 9)
        val acc = RgbaImage(src.width, src.height, IntArray(src.size))
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = channelAsGray(src, shift)
            val flat = Threshold.normalizeIllumination(channel, radius, floorLevel = 40)
            writeChannel(acc, shift, Threshold.stretch(flat, 0.002, 0.998))
        }
        for (i in 0 until src.size) {
            acc.pixels[i] = acc.pixels[i] or (Px.a(src.pixels[i]) shl 24)
        }
        return acc
    }

    private fun blackAndWhite(src: RgbaImage): RgbaImage {        val gray = src.toGray()
        val radius = max(8, max(src.width, src.height) / 20)
        val flattened = Threshold.normalizeIllumination(gray, radius, floorLevel = 48)
        val window = max(6, min(src.width, src.height) / 48)
        val biLevel = Threshold.adaptiveMean(flattened, window, bias = 10)
        return Blur.majority3x3Gray(biLevel).toRgba()
    }

    private fun enhance(src: RgbaImage): RgbaImage {
        val sharpened = unsharpMask(src, 0.7f, sigma = 1.1)
        return saturateAndContrast(sharpened, saturation = 1.12f, contrast = 0.18f)
    }

    private fun saturateAndContrast(src: RgbaImage, saturation: Float, contrast: Float): RgbaImage {
        val c = contrast.coerceIn(-1f, 1f)
        val factor = if (c >= 0f) 1f + c * 1.5f else 1f + c * 0.9f
        val lut = IntArray(256) { i ->
            ((i - 128f) * factor + 128f).roundToInt().coerceIn(0, 255)
        }
        val out = IntArray(src.size)
        for (i in 0 until src.size) {
            val p = src.pixels[i]
            val r = Px.r(p).toFloat()
            val g = Px.g(p).toFloat()
            val b = Px.b(p).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = lut[(luma + (r - luma) * saturation).roundToInt().coerceIn(0, 255)]
            val ng = lut[(luma + (g - luma) * saturation).roundToInt().coerceIn(0, 255)]
            val nb = lut[(luma + (b - luma) * saturation).roundToInt().coerceIn(0, 255)]
            out[i] = Px.argb(Px.a(p), nr, ng, nb)
        }
        return RgbaImage(src.width, src.height, out)
    }

    // ---------------------------------------------------------------- helpers

    private fun channelAsGray(src: RgbaImage, shift: Int): GrayImage {
        val data = IntArray(src.size)
        for (i in 0 until src.size) data[i] = (src.pixels[i] ushr shift) and 0xFF
        return GrayImage(src.width, src.height, data)
    }

    private fun writeChannel(dst: RgbaImage, shift: Int, channel: GrayImage) {
        val mask = (0xFF shl shift).inv()
        for (i in 0 until dst.size) {
            dst.pixels[i] = (dst.pixels[i] and mask) or (channel.data[i].coerceIn(0, 255) shl shift)
        }
    }
}
