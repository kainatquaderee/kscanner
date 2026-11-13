package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * End-to-end transform for a single page: rotate, correct perspective, then enhance.
 *
 * Kept framework-free so the exact pipeline the app runs is also the one the tests run.
 */
object ScanProcessor {

    data class Settings(
        /** Outline in the coordinate space of the rotated source, or null to keep the full frame. */
        val quad: Quad? = null,
        val rotationDegrees: Int = 0,
        /** Residual text skew to remove after cropping, in degrees (clockwise positive). */
        val deskewDegrees: Double = 0.0,
        val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
        val strength: Float = 1f,
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val sharpness: Float = 0f,
        /** Longest output edge in pixels; caps memory on very large captures. */
        val maxOutputEdge: Int = 2600,
    )

    /** Full-quality pass used when persisting a page. */
    fun process(source: RgbaImage, settings: Settings): RgbaImage {
        var image = rotateIfNeeded(source, settings.rotationDegrees)
        image = cropIfNeeded(image, settings.quad, settings.maxOutputEdge)
        image = deskewIfNeeded(image, settings.deskewDegrees)
        image = Filters.apply(image, settings.filter, settings.strength)
        image = applyManual(image, settings)
        return image
    }

    /** Cheap pass for on-screen previews; same math at a reduced resolution. */
    fun preview(source: RgbaImage, settings: Settings, previewEdge: Int = 900): RgbaImage {
        var image = rotateIfNeeded(source, settings.rotationDegrees)

        val longest = max(image.width, image.height)
        val scale = if (longest > previewEdge) previewEdge.toDouble() / longest else 1.0
        if (scale < 1.0) {
            image = Transforms.resize(
                image,
                max(1, (image.width * scale).toInt()),
                max(1, (image.height * scale).toInt()),
            )
        }

        val previewQuad = settings.quad?.scaled(scale, scale)
        image = cropIfNeeded(image, previewQuad, previewEdge)
        image = deskewIfNeeded(image, settings.deskewDegrees)
        image = Filters.apply(image, settings.filter, settings.strength)
        image = applyManual(image, settings)

        val outLongest = max(image.width, image.height)
        if (outLongest > previewEdge) {
            val shrink = previewEdge.toDouble() / outLongest
            image = Transforms.resize(
                image,
                max(1, (image.width * shrink).toInt()),
                max(1, (image.height * shrink).toInt()),
            )
        }
        return image
    }

    private fun applyManual(image: RgbaImage, settings: Settings): RgbaImage {
        return if (settings.brightness != 0f || settings.contrast != 0f || settings.sharpness != 0f) {
            Filters.adjust(image, settings.brightness, settings.contrast, settings.sharpness)
        } else {
            image
        }
    }

    private fun rotateIfNeeded(source: RgbaImage, degrees: Int): RgbaImage {
        val normalized = ((degrees % 360) + 360) % 360
        return if (normalized == 0) source else Transforms.rotate(source, normalized)
    }

    private fun deskewIfNeeded(image: RgbaImage, degrees: Double): RgbaImage =
        if (abs(degrees) < 0.08) image else Transforms.rotateArbitrary(image, degrees)

    private fun cropIfNeeded(source: RgbaImage, quad: Quad?, maxEdge: Int): RgbaImage {
        if (quad == null) return source
        val clamped = quad.clampTo(source.width, source.height)
        if (clamped.area() < 64.0) return source
        if (!clamped.isConvex()) return source

        // A frame-sized outline adds nothing but resampling loss.
        val frame = source.width.toDouble() * source.height
        if (clamped.area() / frame > 0.995) return source

        return Homography.warpPerspective(source, clamped, maxEdge)
    }

    /** Output size that [process] would produce, without doing the work. */
    fun predictOutputSize(source: RgbaImage, settings: Settings): Pair<Int, Int> {
        var w = source.width
        var h = source.height
        val rotation = ((settings.rotationDegrees % 360) + 360) % 360
        if (rotation == 90 || rotation == 270) {
            val t = w; w = h; h = t
        }
        val quad = settings.quad
        if (quad != null && quad.area() > 64.0) {
            val (qw, qh) = Homography.outputSizeFor(quad, settings.maxOutputEdge)
            return Pair(min(qw, settings.maxOutputEdge), min(qh, settings.maxOutputEdge))
        }
        val longest = max(w, h)
        if (longest > settings.maxOutputEdge) {
            val s = settings.maxOutputEdge.toDouble() / longest
            w = (w * s).toInt()
            h = (h * s).toInt()
        }
        return Pair(max(1, w), max(1, h))
    }
}
